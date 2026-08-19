package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kanal z api/channel/grid. Mapovanie poli prebrane z Enigma2 pluginu
 * (_data_api.py / _picons.py): uuid, name, number, icon_public_url
 * (imagecache/NNNN), tags (zoznam tag uuid), services.
 */
@Serializable
data class Channel(
    val uuid: String,
    val name: String = "",
    @SerialName("number") val number: Int? = null,
    @SerialName("icon_public_url") val iconPublicUrl: String? = null,
    val tags: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    /**
     * M504: TYPY sluzieb kanala z DVB tabuliek — "SDTV", "HDTV", "UHDTV",
     * "Radio", "FM Radio", "MPEG2 Radio"... HTSP ich posiela v `channelAdd`
     * (pole `services`, kazda ma `type`), HTTP ich treba dohladat v
     * api/mpegts/service/grid. Prazdne = server ich neposkytol.
     */
    @SerialName("service_types") val serviceTypes: List<String> = emptyList(),
    @SerialName("enabled") val enabled: Boolean = true
) {
    /**
     * M504: je kanal radio podla typu sluzby? Rovnako to urcuje aj Kodi
     * (pvr.hts): typ obsahujuci "radio" znamena rozhlas. Je to hodnota priamo
     * z DVB tabuliek, nie odhad podla nazvu.
     *
     * null = server typy neposlal a musi sa pouzit zaloha (nazvy tagov).
     */
    val isRadioByService: Boolean?
        get() {
            if (serviceTypes.isEmpty()) return null
            return serviceTypes.any { it.contains("radio", ignoreCase = true) }
        }
}
