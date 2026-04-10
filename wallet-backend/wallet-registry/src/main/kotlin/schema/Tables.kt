package cz.majny.wallet.registry.schema

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/* Wallets (singlesig and multisig). */
object WalletsTable : Table("wallets") {
    val walletId = text("wallet_id")
    val network = text("network")
    val type = text("type")              // SINGLE_SIG / MULTI_SIG
    val scriptType = text("script_type") // WPKH / WSH
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

/* Cosigners — one xpub at a specific derivation path. */
object CosignersTable : Table("cosigners") {
    val cosignerId = text("cosigner_id")
    val fingerprint = text("fingerprint")
    val originPath = text("origin_path")
    val xpubRoot = text("xpub_root")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(cosignerId)
}

/* Links cosigners to wallets with their position (idx = 0, 1, 2). */
object WalletCosignersTable : Table("wallet_cosigners") {
    val walletId = text("wallet_id").references(WalletsTable.walletId)
    val idx = integer("idx")
    val cosignerId = text("cosigner_id").references(CosignersTable.cosignerId)
    override val primaryKey = PrimaryKey(walletId, idx)
}

/* Links devices to wallets. account_index tracks which BIP-48 account imported it. */
object WalletMembersTable : Table("wallet_members") {
    val walletId = text("wallet_id").references(WalletsTable.walletId)
    val deviceId = text("device_id")
    val accountIndex = integer("account_index").default(-1)
    val label = text("label").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(walletId, deviceId, accountIndex)
}

/* Pre-derived Bitcoin addresses (20 receive + 20 change per wallet). */
object WalletAddressesTable : Table("wallet_addresses") {
    val walletId = text("wallet_id").references(WalletsTable.walletId)
    val addressType = text("address_type")   // "receive" or "change"
    val addressIndex = integer("address_index")
    val address = text("address")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(walletId, addressType, addressIndex)
}
