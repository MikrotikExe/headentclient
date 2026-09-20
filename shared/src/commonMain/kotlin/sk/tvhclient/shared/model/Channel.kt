package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Channel from api/channel/grid. Field mapping taken from the Enigma2 plugin
 * (_data_api.py / _picons.py): uuid, name, number, icon_public_url
 * (imagecache/NNNN), tags (list of tag uuids), services.
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
     * M504: channel service TYPES from the DVB tables — "SDTV", "HDTV", "UHDTV",
     * "Radio", "FM Radio", "MPEG2 Radio"... HTSP sends them in `channelAdd`
     * (the `services` array, each one has a `type`), over HTTP they have to be
     * looked up in api/mpegts/service/grid. Empty = the server did not provide them.
     */
    @SerialName("service_types") val serviceTypes: List<String> = emptyList(),
    @SerialName("enabled") val enabled: Boolean = true
) {
    /**
     * M504: is the channel a radio station according to its service type? Kodi
     * (pvr.hts) determines it the same way: a type containing "radio" means radio.
     * This is a value straight from the DVB tables, not a guess based on the name.
     *
     * null = the server did not send the types and the fallback (tag names) must be used.
     */
    val isRadioByService: Boolean?
        get() {
            if (serviceTypes.isEmpty()) return null
            return serviceTypes.any { it.contains("radio", ignoreCase = true) }
        }
}
