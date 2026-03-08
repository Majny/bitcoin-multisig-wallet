package cz.majny.wallet.psbt.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/* PSBT records — stores transaction data, signing status, and Trezor Connect params. */
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

/* Signature records — tracks which cosigners have signed each PSBT. */
object PsbtSignaturesTable : Table("psbt_signatures") {
    val id = uuid("id").autoGenerate()
    val psbtId = uuid("psbt_id").references(PsbtsTable.id)
    val deviceId = varchar("device_id", 255)
    val fingerprint = varchar("fingerprint", 16)
    val cosignerIndex = integer("cosigner_index").default(0)
    val signedAt = timestampWithTimeZone("signed_at")

    override val primaryKey = PrimaryKey(id)
}
