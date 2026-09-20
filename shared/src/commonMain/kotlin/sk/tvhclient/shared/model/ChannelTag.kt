package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tag/category from api/channeltag/grid. The uuid is used to filter
 * channels (Channel.tags contains these uuids).
 */
@Serializable
data class ChannelTag(
    val uuid: String,
    val name: String = "",
    @SerialName("index") val index: Int = 999999,
    @SerialName("enabled") val enabled: Boolean = true
)
