package cz.majny.wallet.registry.schema

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object DevicesTable : Table("devices") {
    val deviceId = text("device_id")
    val fingerprint = text("fingerprint")
    val model = text("model").nullable()
    val label = text("label").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(deviceId)
}

object WalletsTable : Table("wallets") {
    val walletId = text("wallet_id")
    val network = text("network")
    val type = text("type")              // SINGLE_SIG / MULTI_SIG
    val scriptType = text("script_type") // WPKH/TR/WSH/SH_WSH...
    val m = integer("m").nullable()
    val n = integer("n").nullable()
    val accountIndex = integer("account_index")
    val birthHeight = integer("birth_height").nullable()
    val label = text("label").nullable()
    val receiveDescriptor = text("receive_descriptor")
    val changeDescriptor = text("change_descriptor")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(walletId)
}

object CosignersTable : Table("cosigners") {
    val cosignerId = text("cosigner_id")
    val fingerprint = text("fingerprint")
    val originPath = text("origin_path")
    val xpubRoot = text("xpub_root")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(cosignerId)
}

object WalletCosignersTable : Table("wallet_cosigners") {
    val walletId = text("wallet_id").references(WalletsTable.walletId)
    val idx = integer("idx")
    val cosignerId = text("cosigner_id").references(CosignersTable.cosignerId)
    override val primaryKey = PrimaryKey(walletId, idx)
}

object WalletMembersTable : Table("wallet_members") {
    val walletId = text("wallet_id").references(WalletsTable.walletId)
    val deviceId = text("device_id").references(DevicesTable.deviceId)
    val accountIndex = integer("account_index").default(-1)  // -1 = singlesig / unknown
    val label = text("label").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(walletId, deviceId, accountIndex)
}

object WalletAddressesTable : Table("wallet_addresses") {
    val walletId = text("wallet_id").references(WalletsTable.walletId)
    val addressType = text("address_type")   // "receive" or "change"
    val addressIndex = integer("address_index")
    val address = text("address")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(walletId, addressType, addressIndex)
}
