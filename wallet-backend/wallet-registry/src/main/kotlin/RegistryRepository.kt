package cz.majny.wallet.registry

import cz.majny.wallet.registry.api.*
import cz.majny.wallet.registry.schema.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class RegistryRepository {

    private val log = LoggerFactory.getLogger(RegistryRepository::class.java)

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

    fun createWallet(req: CreateWalletRequest): WalletDetail = transaction {
        if (req.type == "MULTI_SIG") {
            require(req.m != null && req.n != null) { "MULTI_SIG requires m and n" }
            require(req.cosigners.isNotEmpty()) { "MULTI_SIG requires cosigners" }
        }

        // Idempotent: return existing wallet if already created (e.g., on re-login)
        val existing = WalletsTable.selectAll()
            .where { WalletsTable.walletId eq req.walletId }
            .singleOrNull()
        if (existing != null) {
            log.debug("Wallet {} already exists, skipping creation", req.walletId)
            return@transaction getWallet(req.walletId) ?: error("Wallet ${req.walletId} not found after existence check")
        }

        WalletsTable.insert {
            it[walletId] = req.walletId
            it[network] = req.network
            it[type] = req.type
            it[scriptType] = req.scriptType
            it[m] = req.m
            it[n] = req.n
            it[accountIndex] = req.accountIndex
            it[birthHeight] = req.birthHeight
            it[label] = req.label
            it[receiveDescriptor] = req.receiveDescriptor
            it[changeDescriptor] = req.changeDescriptor
            it[createdAt] = OffsetDateTime.now()
        }

        // cosigners (multisig)
        req.cosigners.forEach { c ->
            val exists = CosignersTable
                .selectAll()
                .where { CosignersTable.cosignerId eq c.cosignerId }
                .singleOrNull()

            if (exists == null) {
                CosignersTable.insert {
                    it[cosignerId] = c.cosignerId
                    it[fingerprint] = c.fingerprint
                    it[originPath] = c.originPath
                    it[xpubRoot] = c.xpubRoot
                    it[createdAt] = OffsetDateTime.now()
                }
            }

            WalletCosignersTable.insert {
                it[walletId] = req.walletId
                it[idx] = c.idx
                it[cosignerId] = c.cosignerId
            }
        }

        req.members.forEach { mem ->
            WalletMembersTable.insertIgnore {
                it[walletId] = req.walletId
                it[deviceId] = mem.deviceId
                it[accountIndex] = mem.accountIndex
                it[createdAt] = OffsetDateTime.now()
            }
        }

        // Derive and store receive + change addresses
        deriveAndStoreAddresses(req.walletId, req.receiveDescriptor, req.network, "receive")
        deriveAndStoreAddresses(req.walletId, req.changeDescriptor, req.network, "change")

        getWallet(req.walletId) ?: error("Wallet insert failed")
    }

    fun attachMember(walletId: String, deviceId: String, accountIndex: Int = -1, label: String? = null) = transaction {
        WalletMembersTable.insertIgnore {
            it[WalletMembersTable.walletId] = walletId
            it[WalletMembersTable.deviceId] = deviceId
            it[WalletMembersTable.accountIndex] = accountIndex
            if (label != null) it[WalletMembersTable.label] = label
            it[createdAt] = OffsetDateTime.now()
        }
    }

    fun listWalletsForDevice(deviceId: String): List<WalletSummary> = transaction {
        val rows = (WalletsTable innerJoin WalletMembersTable)
            .select(
                WalletsTable.walletId,
                WalletsTable.network,
                WalletsTable.type,
                WalletsTable.scriptType,
                WalletsTable.m,
                WalletsTable.n,
                WalletsTable.label,
                WalletsTable.accountIndex,
                WalletMembersTable.accountIndex,
                WalletMembersTable.label
            )
            .where { WalletMembersTable.deviceId eq deviceId }

        val result = rows.map { row ->
            val memberAccountIndex = row[WalletMembersTable.accountIndex]

            // For multisig: use the member's account_index directly.
            // -1 means unknown → null (show on all accounts as fallback).
            // For singlesig: use the wallet's own account_index.
            val resolvedAccountIndex = if (row[WalletsTable.type] == "MULTI_SIG") {
                if (memberAccountIndex >= 0) memberAccountIndex else null
            } else {
                row[WalletsTable.accountIndex]
            }

            // Prefer per-member label over global wallet label
            val resolvedLabel = row[WalletMembersTable.label] ?: row[WalletsTable.label]

            WalletSummary(
                walletId = row[WalletsTable.walletId],
                network = row[WalletsTable.network],
                type = row[WalletsTable.type],
                scriptType = row[WalletsTable.scriptType],
                m = row[WalletsTable.m],
                n = row[WalletsTable.n],
                label = resolvedLabel,
                accountIndex = resolvedAccountIndex
            )
        }
        log.info("listWalletsForDevice({}): returning {} wallets: {}",
            deviceId, result.size, result.map { "${it.walletId}(${it.type},ai=${it.accountIndex})" })
        result
    }

    fun getWallet(walletId: String): WalletDetail? = transaction {
        val w = WalletsTable
            .selectAll()
            .where { WalletsTable.walletId eq walletId }
            .singleOrNull() ?: return@transaction null

        val cosigners = (WalletCosignersTable innerJoin CosignersTable)
            .select(
                WalletCosignersTable.idx,
                CosignersTable.cosignerId,
                CosignersTable.fingerprint,
                CosignersTable.originPath,
                CosignersTable.xpubRoot
            )
            .where { WalletCosignersTable.walletId eq walletId }
            .map { row ->
                CosignerInWallet(
                    idx = row[WalletCosignersTable.idx],
                    cosignerId = row[CosignersTable.cosignerId],
                    fingerprint = row[CosignersTable.fingerprint],
                    originPath = row[CosignersTable.originPath],
                    xpubRoot = row[CosignersTable.xpubRoot]
                )
            }
            .sortedBy { it.idx }

        val members = WalletMembersTable
            .select(WalletMembersTable.deviceId, WalletMembersTable.accountIndex)
            .where { WalletMembersTable.walletId eq walletId }
            .map { row ->
                MemberAttach(
                    deviceId = row[WalletMembersTable.deviceId],
                    accountIndex = row[WalletMembersTable.accountIndex]
                )
            }

        WalletDetail(
            walletId = w[WalletsTable.walletId],
            network = w[WalletsTable.network],
            type = w[WalletsTable.type],
            scriptType = w[WalletsTable.scriptType],
            m = w[WalletsTable.m],
            n = w[WalletsTable.n],
            accountIndex = w[WalletsTable.accountIndex],
            birthHeight = w[WalletsTable.birthHeight],
            label = w[WalletsTable.label],
            receiveDescriptor = w[WalletsTable.receiveDescriptor],
            changeDescriptor = w[WalletsTable.changeDescriptor],
            cosigners = cosigners,
            members = members
        )
    }

    // ---- Address derivation & storage ----

    /**
     * Derives addresses from a descriptor and stores them in wallet_addresses.
     */
    private fun deriveAndStoreAddresses(
        walletId: String,
        descriptor: String,
        network: String,
        type: String,
        count: Int = AddressDerivation.DEFAULT_GAP_LIMIT
    ) {
        val chain = if (type == "receive") 0 else 1
        try {
            val addresses = AddressDerivation.deriveAddresses(
                descriptor = descriptor,
                network = network,
                chain = chain,
                fromIndex = 0,
                count = count
            )
            addresses.forEach { derived ->
                WalletAddressesTable.insertIgnore {
                    it[WalletAddressesTable.walletId] = walletId
                    it[addressType] = type
                    it[addressIndex] = derived.index
                    it[address] = derived.address
                    it[createdAt] = OffsetDateTime.now()
                }
            }
            log.info("Derived {} {} addresses for wallet {}", addresses.size, type, walletId)
        } catch (e: Exception) {
            log.error("Failed to derive {} addresses for wallet {}: {}", type, walletId, e.message, e)
        }
    }

    /**
     * Returns all stored addresses for a wallet.
     */
    fun getAddresses(
        walletId: String,
        type: String? = null
    ): List<WalletAddressResponse> = transaction {
        val query = WalletAddressesTable
            .selectAll()
            .where { WalletAddressesTable.walletId eq walletId }

        if (type != null) {
            query.andWhere { WalletAddressesTable.addressType eq type }
        }

        query
            .orderBy(WalletAddressesTable.addressIndex)
            .map { row ->
                WalletAddressResponse(
                    walletId = walletId,
                    address = row[WalletAddressesTable.address],
                    index = row[WalletAddressesTable.addressIndex],
                    type = row[WalletAddressesTable.addressType]
                )
            }
    }

    /**
     * Returns a single address by wallet, type and index.
     */
    fun getAddress(
        walletId: String,
        type: String = "receive",
        index: Int = 0
    ): WalletAddressResponse? = transaction {
        WalletAddressesTable
            .selectAll()
            .where {
                (WalletAddressesTable.walletId eq walletId) and
                (WalletAddressesTable.addressType eq type) and
                (WalletAddressesTable.addressIndex eq index)
            }
            .singleOrNull()
            ?.let { row ->
                WalletAddressResponse(
                    walletId = walletId,
                    address = row[WalletAddressesTable.address],
                    index = row[WalletAddressesTable.addressIndex],
                    type = row[WalletAddressesTable.addressType]
                )
            }
    }
}
