package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Verify Android package identity before offering installation, independently of the release tag. */
internal object UpdatePackageVerifier {
    data class Identity(val packageName: String, val versionCode: Long, val signers: Set<String>)

    internal fun accepts(installed: Identity, candidate: Identity): Boolean =
        installed.packageName == candidate.packageName && candidate.versionCode > installed.versionCode &&
            installed.signers.isNotEmpty() && installed.signers == candidate.signers

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val installed = manager.getPackageInfo(context.packageName, flags)
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Update rejected: unreadable APK")
        if (!accepts(identity(installed), identity(candidate))) {
            throw IOException("Update rejected: package, signature or versionCode does not match an upgrade")
        }
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): Identity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val signers = signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        return Identity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(), signers)
    }
}
