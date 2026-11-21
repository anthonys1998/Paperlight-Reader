package com.paperlight.reader.data

enum class BackupProgressStage(val label: String) {
    Scanning("Checking book files"),
    Preparing("Preparing backup"),
    ProtectingArchive("Creating encrypted backup"),
    Decrypting("Checking password and decrypting"),
    Validating("Validating backup"),
    Staging("Preparing books"),
    Publishing("Finishing restore"),
    Finalizing("Applying settings and reader data"),
}

enum class BackupProgressUnit { Bytes, Books }

data class BackupProgress(
    val stage: BackupProgressStage,
    val completed: Long = 0L,
    val total: Long? = null,
    val unit: BackupProgressUnit = BackupProgressUnit.Bytes,
)
