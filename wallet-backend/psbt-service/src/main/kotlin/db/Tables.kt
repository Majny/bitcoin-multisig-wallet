package cz.majny.wallet.psbt.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/*
 * Stores PSBTs in their various lifecycle states (pending, signed, broadcast).
 * trezor_connect_params is a serialized JSON blob — we don't query into it,
 * just hand the whole thing back to the client.
 */
object PsbtsTable : Table("psbts") {
    val id = uuid("id").autoGenerate()
    val walletId = varchar("wallet_id", 255)
    val psbtBase64 = text("psbt_base64")
    val status = varchar("status", 50).default("pending")       // pending → signed → broadcast
    val txType = varchar("tx_type", 50).default("send")
    val requiredSigs = integer("required_sigs").default(1)
    val currentSigs = integer("current_sigs").default(0)
    val totalOutputSats = long("total_output_sats").default(0)
    val estimatedFeeSats = long("estimated_fee_sats").default(0)
    val label = varchar("label", 255).nullable()
    val txid = varchar("txid", 64).nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val broadcastAt = timestampWithTimeZone("broadcast_at").nullable()
    val trezorConnectParams = text("trezor_connect_params").nullable()
    val serializedTx = text("serialized_tx").nullable()

    override val primaryKey = PrimaryKey(id)
}

/*
 * Per-cosigner audit trail. (psbt_id, cosigner_index) is unique so the same
 * cosigner can't be recorded as having signed twice.
 */
object PsbtSignaturesTable : Table("psbt_signatures") {
    val id = uuid("id").autoGenerate()
    val psbtId = uuid("psbt_id").references(PsbtsTable.id)
    val deviceId = varchar("device_id", 255)
    val fingerprint = varchar("fingerprint", 16)
    val cosignerIndex = integer("cosigner_index").default(0)
    val signedAt = timestampWithTimeZone("signed_at")

    override val primaryKey = PrimaryKey(id)
}
