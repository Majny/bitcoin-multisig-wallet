package cz.majny.wallet.authservice

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

object DevicesTable : Table("devices") {
    val deviceId = text("device_id")
    val fingerprint = text("fingerprint")
    val model = text("model").nullable()
    val label = text("label").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(deviceId)
}

@Serializable
data class UpsertDeviceRequest(
    val deviceId: String,
    val fingerprint: String,
    val model: String? = null,
    val label: String? = null
)

@Serializable
data class DeviceResponse(
    val deviceId: String,
    val fingerprint: String,
    val model: String? = null,
    val label: String? = null
)

class DeviceRepository {

    fun upsertDevice(req: UpsertDeviceRequest): DeviceResponse = transaction {
        val existing = DevicesTable
            .selectAll()
            .where { DevicesTable.deviceId eq req.deviceId }
            .singleOrNull()

        if (existing == null) {
            DevicesTable.insert {
                it[deviceId] = req.deviceId
                it[fingerprint] = req.fingerprint
                it[model] = req.model
                it[label] = req.label
                it[createdAt] = OffsetDateTime.now()
            }
        } else {
            DevicesTable.update({ DevicesTable.deviceId eq req.deviceId }) {
                it[fingerprint] = req.fingerprint
                it[model] = req.model
                it[label] = req.label
            }
        }

        DeviceResponse(
            deviceId = req.deviceId,
            fingerprint = req.fingerprint,
            model = req.model,
            label = req.label
        )
    }

    fun getDevice(deviceId: String): DeviceResponse? = transaction {
        DevicesTable
            .selectAll()
            .where { DevicesTable.deviceId eq deviceId }
            .singleOrNull()
            ?.let {
                DeviceResponse(
                    deviceId = it[DevicesTable.deviceId],
                    fingerprint = it[DevicesTable.fingerprint],
                    model = it[DevicesTable.model],
                    label = it[DevicesTable.label]
                )
            }
    }
}
