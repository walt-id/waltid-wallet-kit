package id.walt.verifier.backend

import com.beust.klaxon.Klaxon
import id.walt.webwallet.backend.config.ExternalHostnameUrl
import id.walt.webwallet.backend.config.externalHostnameUrlValueConverter
import java.io.File

data class VerifierConfig(
  @ExternalHostnameUrl val externalUrl: String = "http://localhost:4000",
  val wallets: Map<String, WalletConfiguration> = WalletConfiguration.getDefaultWalletConfigurations()
) {
  companion object {
    val CONFIG_FILE = "${id.walt.WALTID_DATA_ROOT}/config/verifier-config.json"
    lateinit var config: VerifierConfig
    init {
      val cf = File(CONFIG_FILE)
      if(cf.exists()) {
        config = Klaxon().fieldConverter(ExternalHostnameUrl::class, externalHostnameUrlValueConverter).parse<VerifierConfig>(cf) ?: VerifierConfig()
      } else {
        config = VerifierConfig()
      }
    }
  }
}
