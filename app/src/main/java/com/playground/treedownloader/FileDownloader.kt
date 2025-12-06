package com.playground.treedownloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

object FileDownloader {
    private const val TAG = "FileDownloader"
    
    enum class DirectoryType(val directoryName: String, val displayName: String) {
        DOWNLOADS(Environment.DIRECTORY_DOWNLOADS, "Downloads"),
        DCIM(Environment.DIRECTORY_DCIM, "DCIM")
    }
    
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    
    suspend fun downloadAllFilesTo(context: Context, uri: Uri, folder: String, directoryType: DirectoryType = DirectoryType.DOWNLOADS) {
        withContext(Dispatchers.IO) {
            try {
                _downloadProgress.value = 0f
                val baseUrl = ensureTrailingSlash(uri.toString())
                // Use selected directory (Downloads or DCIM)
                val publicDir = Environment.getExternalStoragePublicDirectory(directoryType.directoryName)
                val downloadDir = File(publicDir, folder)
                downloadDir.mkdirs()
                
                Log.d(TAG, "Starting download from $baseUrl to ${downloadDir.absolutePath}")
                
                // Count total files first
                val totalFiles = countFiles(baseUrl, "")
                Log.d(TAG, "Total files to download: $totalFiles")
                
                if (totalFiles == 0) {
                    _downloadProgress.value = 1f
                    Log.d(TAG, "No files to download")
                    return@withContext
                }
                
                // Recursively download all files from the directory
                var downloadedFiles = 0
                downloadDirectory(context, baseUrl, "", downloadDir, directoryType) { 
                    downloadedFiles++
                    _downloadProgress.value = downloadedFiles.toFloat() / totalFiles
                }
                
                _downloadProgress.value = 1f
                Log.d(TAG, "Download completed. Files saved to: ${downloadDir.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error during download", e)
                _downloadProgress.value = 0f
            }
        }
    }
    
    private suspend fun countFiles(baseUrl: String, relativePath: String): Int {
        val directoryUrl = if (relativePath.isEmpty()) {
            baseUrl
        } else {
            // Ensure path ends with / for directory
            val pathWithSlash = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            buildUrl(baseUrl, pathWithSlash)
        }
        
        val listingContent = downloadFile(directoryUrl)
        if (listingContent == null) {
            return 0
        }
        
        // Parse HTML directory listing
        val items = parseDirectoryListing(listingContent)
        var fileCount = 0
        
        for (item in items) {
            val itemPath = if (relativePath.isEmpty()) {
                item.name
            } else {
                "$relativePath/${item.name}"
            }
            
            if (item.isDirectory) {
                // Recursively count files in subdirectory
                fileCount += countFiles(baseUrl, itemPath)
            } else {
                // Count file
                fileCount++
            }
        }
        
        return fileCount
    }
    
    private suspend fun downloadDirectory(context: Context, baseUrl: String, relativePath: String, targetDir: File, directoryType: DirectoryType, onFileDownloaded: () -> Unit = {}) {
        val directoryUrl = if (relativePath.isEmpty()) {
            baseUrl
        } else {
            // Ensure path ends with / for directory
            val pathWithSlash = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
            buildUrl(baseUrl, pathWithSlash)
        }
        
        Log.d(TAG, "Fetching directory listing: $directoryUrl")
        
        val listingContent = downloadFile(directoryUrl)
        if (listingContent == null) {
            Log.e(TAG, "Failed to fetch directory listing from $directoryUrl (relative path: $relativePath)")
            return
        }
        
        // Parse HTML directory listing
        val items = parseDirectoryListing(listingContent)
        Log.d(TAG, "Found ${items.size} items in $relativePath")
        
        for (item in items) {
            val itemPath = if (relativePath.isEmpty()) {
                item.name
            } else {
                "$relativePath/${item.name}"
            }
            
            val targetFile = File(targetDir, itemPath)
            
            if (item.isDirectory) {
                // Recursively download subdirectory
                targetFile.mkdirs()
                downloadDirectory(context, baseUrl, itemPath, targetDir, directoryType, onFileDownloaded)
            } else {
                // Download file
                val fileUrl = buildUrl(baseUrl, itemPath)
                
                targetFile.parentFile?.mkdirs()
                Log.d(TAG, "Downloading: $itemPath")
                
                val success = downloadFileToPath(fileUrl, targetFile, context, directoryType)
                if (success) {
                    Log.d(TAG, "Successfully downloaded: $itemPath")
                    onFileDownloaded()
                } else {
                    Log.e(TAG, "Failed to download: $itemPath")
                }
            }
        }
    }
    
    private data class DirectoryItem(val name: String, val isDirectory: Boolean)
    
    private fun parseDirectoryListing(html: String): List<DirectoryItem> {
        val items = mutableListOf<DirectoryItem>()
        
        // Pattern to match <a href="...">...</a> links
        // Common patterns:
        // - <a href="filename">filename</a>
        // - <a href="filename/">filename/</a>
        // - <a href="../">../</a> (skip parent directory)
        
        // Match <a href="...">...</a> with various formats
        val linkPattern = Pattern.compile(
            "<a\\s+href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE
        )
        
        val matcher = linkPattern.matcher(html)
        while (matcher.find()) {
            val href = matcher.group(1) ?: continue
            val linkText = matcher.group(2)?.trim() ?: continue
            
            // Skip parent directory and current directory
            if (href == "../" || href == "./" || href == ".." || href == ".") {
                continue
            }
            
            // Skip absolute URLs (like http://)
            if (href.startsWith("http://") || href.startsWith("https://")) {
                continue
            }
            
            // Decode URL-encoded names (handles both encoded and unencoded hrefs)
            // If href is already encoded (e.g., "file%20name.txt"), decode it
            // If href is not encoded (e.g., "file name.txt"), decoding has no effect
            val decodedName = try {
                URLDecoder.decode(href, StandardCharsets.UTF_8.toString())
            } catch (e: Exception) {
                // If decoding fails, use the original href
                href
            }
            
            // Remove trailing slash to get the name
            val name = if (decodedName.endsWith("/")) {
                decodedName.dropLast(1)
            } else {
                decodedName
            }
            
            // Determine if it's a directory (ends with / in href or link text)
            val isDirectory = href.endsWith("/") || linkText.endsWith("/")
            
            // Skip empty names
            if (name.isNotEmpty()) {
                items.add(DirectoryItem(name, isDirectory))
            }
        }
        
        // If no links found with the pattern, try a simpler approach
        // Look for common directory listing patterns
        if (items.isEmpty()) {
            // Try to find table rows or list items with file names
            val tableRowPattern = Pattern.compile(
                "<tr[^>]*>.*?<a[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([^<]+)</a>.*?</tr>",
                Pattern.CASE_INSENSITIVE or Pattern.DOTALL
            )
            val tableMatcher = tableRowPattern.matcher(html)
            while (tableMatcher.find()) {
                val href = tableMatcher.group(1) ?: continue
                val linkText = tableMatcher.group(2)?.trim() ?: continue
                
                if (href == "../" || href == "./" || href == ".." || href == ".") {
                    continue
                }
                
                if (href.startsWith("http://") || href.startsWith("https://")) {
                    continue
                }
                
                // Decode URL-encoded names (handles both encoded and unencoded hrefs)
                val decodedName = try {
                    URLDecoder.decode(href, StandardCharsets.UTF_8.toString())
                } catch (e: Exception) {
                    href
                }
                
                val name = if (decodedName.endsWith("/")) {
                    decodedName.dropLast(1)
                } else {
                    decodedName
                }
                
                val isDirectory = href.endsWith("/") || linkText.endsWith("/")
                
                if (name.isNotEmpty()) {
                    items.add(DirectoryItem(name, isDirectory))
                }
            }
        }
        
        return items.distinctBy { it.name } // Remove duplicates
    }
    
    private fun buildUrl(baseUrl: String, path: String): String {
        return try {
            val baseUri = URI(baseUrl)
            
            if (path.isEmpty()) {
                baseUri.toString()
            } else {
                // Use URI constructor to properly encode the path
                // The path will be automatically percent-encoded by URI
                // Ensure path starts with / for proper resolution
                val pathToResolve = if (path.startsWith("/")) path else "/$path"
                
                // Create a URI with just the path - URI will encode it properly
                val pathUri = try {
                    URI(null, null, pathToResolve, null, null)
                } catch (e: Exception) {
                    // If that fails, try encoding segments manually
                    val encodedSegments = path.split("/").map { segment ->
                        if (segment.isEmpty()) {
                            segment
                        } else {
                            // Percent-encode the segment properly
                            segment.encodeToByteArray().joinToString("") { byte ->
                                val unsigned = byte.toInt() and 0xFF
                                when {
                                    (unsigned >= 'A'.code && unsigned <= 'Z'.code) ||
                                    (unsigned >= 'a'.code && unsigned <= 'z'.code) ||
                                    (unsigned >= '0'.code && unsigned <= '9'.code) ||
                                    unsigned == '-'.code || unsigned == '.'.code ||
                                    unsigned == '_'.code || unsigned == '~'.code -> unsigned.toChar().toString()
                                    else -> "%${unsigned.toString(16).uppercase().padStart(2, '0')}"
                                }
                            }
                        }
                    }
                    URI(null, null, "/${encodedSegments.joinToString("/")}", null, null)
                }
                
                // Resolve the path URI relative to base URI
                baseUri.resolve(pathUri).toString()
            }
        } catch (e: Exception) {
            // Fallback if URI construction fails completely
            Log.w(TAG, "Failed to build URI properly, using simple concatenation: ${e.message}")
            if (baseUrl.endsWith("/")) {
                "$baseUrl$path"
            } else {
                "$baseUrl/$path"
            }
        }
    }
    
    private fun ensureTrailingSlash(url: String): String {
        return if (url.endsWith("/")) url else "$url/"
    }
    
    private suspend fun downloadFile(urlString: String): String? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.instanceFollowRedirects = true
            connection.connect()
            
            val responseCode = connection.responseCode
            val responseMessage = try {
                connection.responseMessage ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
            
            if (responseCode in 200..299) {
                return connection.inputStream.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        reader.readText()
                    }
                }
            } else {
                // Try to read error stream for debugging
                val errorBody = try {
                    connection.errorStream?.use { errorStream ->
                        errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            reader.readText()
                        }
                    } ?: ""
                } catch (e: Exception) {
                    "Failed to read error stream: ${e.message}"
                }
                
                Log.e(TAG, "HTTP error code: $responseCode, message: $responseMessage, URL: $urlString")
                if (errorBody.isNotEmpty()) {
                    Log.e(TAG, "Error response body: $errorBody")
                }
                return null
            }
        } catch (e: Exception) {
            val responseCode = try {
                connection?.responseCode ?: -1
            } catch (ex: Exception) {
                -1
            }
            val responseMessage = try {
                connection?.responseMessage ?: "N/A"
            } catch (ex: Exception) {
                "N/A"
            }
            
            Log.e(TAG, "Exception downloading $urlString - Error code: $responseCode, Response message: $responseMessage", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}, Message: ${e.message}")
            return null
        } finally {
            connection?.disconnect()
        }
    }
    
    private suspend fun downloadFileToPath(urlString: String, targetFile: File, context: Context, directoryType: DirectoryType): Boolean {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        var success = false
        
        try {
            // Delete existing file to ensure overwrite
            if (targetFile.exists()) {
                targetFile.delete()
            }
            
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()
            
            val responseCode = connection.responseCode
            val responseMessage = try {
                connection.responseMessage ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
            
            if (responseCode in 200..299) {
                inputStream = connection.inputStream
                outputStream = FileOutputStream(targetFile)
                
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                
                outputStream.flush()
                success = true
                
                // Register file with MediaStore so it appears in file pickers
                registerFileWithMediaStore(context, targetFile, directoryType)
            } else {
                // Try to read error stream for debugging
                val errorBody = try {
                    connection.errorStream?.use { errorStream ->
                        errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            reader.readText()
                        }
                    } ?: ""
                } catch (e: Exception) {
                    "Failed to read error stream: ${e.message}"
                }
                
                Log.e(TAG, "HTTP error code: $responseCode, message: $responseMessage, URL: $urlString, target: ${targetFile.absolutePath}")
                if (errorBody.isNotEmpty()) {
                    Log.e(TAG, "Error response body: $errorBody")
                }
                
                // Delete partial file if it exists
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                success = false
            }
        } catch (e: Exception) {
            val responseCode = try {
                connection?.responseCode ?: -1
            } catch (ex: Exception) {
                -1
            }
            val responseMessage = try {
                connection?.responseMessage ?: "N/A"
            } catch (ex: Exception) {
                "N/A"
            }
            
            Log.e(TAG, "Exception downloading file $urlString to ${targetFile.absolutePath} - Error code: $responseCode, Response message: $responseMessage", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}, Message: ${e.message}")
            
            // Delete partial file on exception
            try {
                if (targetFile.exists()) {
                    targetFile.delete()
                }
            } catch (deleteException: Exception) {
                Log.w(TAG, "Failed to delete partial file ${targetFile.absolutePath}", deleteException)
            }
            success = false
        } finally {
            try {
                inputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing input stream", e)
            }
            try {
                outputStream?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing output stream", e)
            }
            connection?.disconnect()
        }
        
        return success
    }
    
    private fun registerFileWithMediaStore(context: Context, file: File, directoryType: DirectoryType) {
        try {
            val mimeType = getMimeType(file.name)
            
            // Use MediaScannerConnection for all Android versions - it's the most reliable
            // This ensures files appear in file pickers and gallery apps
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                object : MediaScannerConnection.OnScanCompletedListener {
                    override fun onScanCompleted(path: String?, uri: Uri?) {
                        if (uri != null) {
                            Log.d(TAG, "Successfully scanned file: ${file.name} -> $uri")
                        } else {
                            Log.w(TAG, "MediaScanner completed but no URI returned for: ${file.name}")
                        }
                    }
                }
            )
            
            // For Android 10+, also try to insert into MediaStore directly
            // This helps ensure immediate visibility
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val basePath = Environment.getExternalStoragePublicDirectory(directoryType.directoryName).absolutePath
                    val filePath = file.absolutePath
                    
                    if (filePath.startsWith(basePath)) {
                        val relativePath = filePath.substring(basePath.length)
                        val pathSegments = relativePath.split("/").filter { it.isNotEmpty() }
                        val dirPath = if (pathSegments.size > 1) {
                            pathSegments.dropLast(1).joinToString("/")
                        } else {
                            ""
                        }
                        
                        val contentValues = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.SIZE, file.length())
                            if (dirPath.isNotEmpty()) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, directoryType.directoryName + "/" + dirPath)
                            } else {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, directoryType.directoryName)
                            }
                        }
                        
                        // Use appropriate MediaStore collection based on file type and directory
                        // Images must go to DCIM or Pictures, not Downloads
                        val collectionUri = when {
                            mimeType.startsWith("image/") -> {
                                // Images must be in DCIM or Pictures
                                if (directoryType == DirectoryType.DCIM) {
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                } else {
                                    // If in Downloads, we can't use Images collection, skip MediaStore insert
                                    // MediaScannerConnection will handle it
                                    null
                                }
                            }
                            mimeType.startsWith("video/") -> {
                                if (directoryType == DirectoryType.DCIM) {
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                                } else {
                                    null
                                }
                            }
                            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        }
                        
                        // Try to insert - if it fails, MediaScannerConnection will handle it
                        if (collectionUri != null) {
                            val uri = context.contentResolver.insert(collectionUri, contentValues)
                            if (uri != null) {
                                Log.d(TAG, "Also registered in MediaStore: ${file.name} -> $uri")
                            }
                        } else {
                            Log.d(TAG, "Skipping MediaStore insert for ${file.name} (wrong directory for file type)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "MediaStore insert failed (MediaScanner will handle it): ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register file with MediaStore: ${file.name}", e)
        }
    }
    
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        return mimeType ?: "application/octet-stream"
    }
}

