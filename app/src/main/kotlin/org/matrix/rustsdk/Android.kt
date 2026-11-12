package org.matrix.rustsdk

import android.content.Context

/**
 * JNI entry point exported by libmatrix_sdk_ffi (`Java_org_matrix_rustsdk_Android_init`).
 *
 * It hands the application context to rustls-platform-verifier so TLS certificates are checked
 * with Android's own trust store (including user-installed CAs). It must run before the SDK makes
 * any HTTPS request, otherwise requests fail with "Expect rustls-platform-verifier to be initialized".
 */
object Android {
    @JvmStatic
    external fun init(context: Context)
}
