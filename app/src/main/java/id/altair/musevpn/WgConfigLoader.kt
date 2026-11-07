package id.altair.musevpn

import com.wireguard.config.Config
import java.io.ByteArrayInputStream

/**
 * Mengubah isi file .conf WireGuard biasa menjadi Config yang hanya mengarahkan
 * aplikasi ini ke tunnel (IncludedApplications), sehingga aplikasi lain tetap
 * memakai koneksi normal dan tidak ikut lewat VPN.
 *
 * Parser resmi WireGuard memang menerima "IncludedApplications" di [Interface]
 * (terverifikasi di Interface.parseLine), jadi penyisipan teks aman dipakai.
 */
object WgConfigLoader {

    private val SECTION = Regex("(?im)^\\s*\\[Interface\\]\\s*$")
    private val APP_PKG = Regex("^[A-Za-z0-9_.]+$")

    fun loadAppOnly(confText: String, packageName: String): Config {
        require(APP_PKG.matches(packageName)) { "nama paket tidak sah" }
        require(confText.contains("[Interface]", ignoreCase = true)) {
            "bukan file WireGuard (.conf) yang sah"
        }
        require(!confText.contains("IncludedApplications", ignoreCase = true) &&
            !confText.contains("ExcludedApplications", ignoreCase = true)) {
            "hapus IncludedApplications/ExcludedApplications dari file .conf; aplikasi mengaturnya sendiri"
        }

        val patched = SECTION.replaceFirst(
            confText,
            "[Interface]\nIncludedApplications = $packageName"
        )
        return Config.parse(ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)))
    }
}
