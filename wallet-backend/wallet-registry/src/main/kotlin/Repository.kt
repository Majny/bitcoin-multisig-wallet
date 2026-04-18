package cz.majny.wallet.registry

import cz.majny.wallet.registry.api.*
import cz.majny.wallet.registry.schema.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime

class Repository {

    private val log = LoggerFactory.getLogger(Repository::class.java)

    /* Creates wallet + cosigners + members + derives addresses. Idempotent. */
    fun createWallet(req: CreateWalletRequest): WalletDetail = transaction {
        require(req.walletId.isNotBlank()) { "walletId must not be blank" }
        require(req.network in listOf("mainnet", "testnet")) { "network must be 'mainnet' or 'testnet', got '${req.network}'" }
        require(req.type in listOf("SINGLE_SIG", "MULTI_SIG")) { "type must be 'SINGLE_SIG' or 'MULTI_SIG', got '${req.type}'" }
        require(req.receiveDescriptor.isNotBlank()) { "receiveDescriptor must not be blank" }
        require(req.changeDescriptor.isNotBlank()) { "changeDescriptor must not be blank" }
        if (req.type == "MULTI_SIG") {
            require(req.m != null && req.n != null) { "MULTI_SIG requires m and n" }
            require(req.m >= 1 && req.m <= req.n) { "MULTI_SIG requires 1 <= m (${ req.m}) <= n (${req.n})" }
            require(req.n <= 15) { "MULTI_SIG n (${req.n}) exceeds maximum of 15" }
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

        // Cosigners: insert into cosigners table (if new) + wallet_cosigners (linking with order)
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

        // Derive and store receive + change addresses (default 20 each)
        deriveAndStoreAddresses(req.walletId, req.receiveDescriptor, req.network, "receive")
        deriveAndStoreAddresses(req.walletId, req.changeDescriptor, req.network, "change")

        getWallet(req.walletId) ?: error("Wallet insert failed")
    }

    /* Attaches a device as member of an existing wallet. insertIgnore skips duplicates. */
    fun attachMember(walletId: String, deviceId: String, accountIndex: Int = -1, label: String? = null) = transaction {
        WalletMembersTable.insertIgnore {
            it[WalletMembersTable.walletId] = walletId
            it[WalletMembersTable.deviceId] = deviceId
            it[WalletMembersTable.accountIndex] = accountIndex
            if (label != null) it[WalletMembersTable.label] = label
            it[createdAt] = OffsetDateTime.now()
        }
    }

    /* Lists wallets for a device. JOINs wallets + wallet_members. */
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

            // Singlesig: account index from wallets table.
            // Multisig: account index from wallet_members (which account imported it).
            // -1 = unknown → null (show on all accounts).
            val resolvedAccountIndex = if (row[WalletsTable.type] == "MULTI_SIG") {
                if (memberAccountIndex >= 0) memberAccountIndex else null
            } else {
                row[WalletsTable.accountIndex]
            }

            val resolvedLabel = row[WalletMembersTable.label] ?: row[WalletsTable.label]

            WalletSummary(
                walletId = row[WalletsTable.walletId],
                network = row[WalletsTable.network],
                type = row[WalletsTable.type],
                scriptType = row[WalletsTable.scriptType],
                m = row[WalletsTable.m],
                n = row[WalletsTable.n],
                label = resolvedLabel,
                accountIndex = resolvedAccountIndex,
                cosignerAccountIndex = if (row[WalletsTable.type] == "MULTI_SIG")
                    row[WalletsTable.accountIndex] else null
            )
        }
        log.info("listWalletsForDevice({}): returning {} wallets: {}",
            deviceId, result.size, result.map { "${it.walletId}(${it.type},ai=${it.accountIndex})" })
        result
    }

    /* Full wallet detail with cosigners and members. Used by psbt-service for PSBT building. */
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
                // label is null here by design — per-device labels are fetched
                // separately via getCosignerLabels(deviceId, walletId) and layered
                // in by the api-gateway. See cosigner_labels table.
                CosignerInWallet(
                    idx = row[WalletCosignersTable.idx],
                    cosignerId = row[CosignersTable.cosignerId],
                    fingerprint = row[CosignersTable.fingerprint],
                    originPath = row[CosignersTable.originPath],
                    xpubRoot = row[CosignersTable.xpubRoot],
                    label = null
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

    /*
     * Upserts the per-device label for a cosigner position.
     * Each Trezor (device_id from JWT) keeps its own labels so they don't leak
     * to other members of the same multisig wallet.
     */
    fun upsertCosignerLabel(
        deviceId: String,
        walletId: String,
        cosignerIdx: Int,
        label: String
    ) = transaction {
        val updated = CosignerLabelsTable.update({
            (CosignerLabelsTable.deviceId eq deviceId) and
                (CosignerLabelsTable.walletId eq walletId) and
                (CosignerLabelsTable.idx eq cosignerIdx)
        }) {
            it[CosignerLabelsTable.label] = label
            it[updatedAt] = OffsetDateTime.now()
        }
        if (updated == 0) {
            CosignerLabelsTable.insert {
                it[CosignerLabelsTable.deviceId] = deviceId
                it[CosignerLabelsTable.walletId] = walletId
                it[CosignerLabelsTable.idx] = cosignerIdx
                it[CosignerLabelsTable.label] = label
                it[updatedAt] = OffsetDateTime.now()
            }
        }
    }

    /* Returns the caller device's labels for all cosigner positions in a wallet. */
    fun getCosignerLabels(
        deviceId: String,
        walletId: String
    ): List<Pair<Int, String>> = transaction {
        CosignerLabelsTable
            .selectAll()
            .where {
                (CosignerLabelsTable.deviceId eq deviceId) and
                    (CosignerLabelsTable.walletId eq walletId)
            }
            .map { row ->
                row[CosignerLabelsTable.idx] to row[CosignerLabelsTable.label]
            }
    }

    /* Derives addresses from a descriptor and stores them in wallet_addresses table. */
    private fun deriveAndStoreAddresses(
        walletId: String,
        descriptor: String,
        network: String,
        type: String,
        count: Int = AddressDerivation.DEFAULT_GAP_LIMIT
    ) {
        val chain = if (type == "receive") 0 else 1
        // No try-catch: let derivation failures propagate so the wallet creation
        // transaction rolls back. A wallet without addresses is useless.
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
    }

    /**
     * Derives a single address beyond the initial gap limit and stores it in DB.
     *
     * Used when all pre-derived addresses (of one type) are already used on-chain
     * and we need to extend the wallet's address window — typically because
     * psbt-service needs a fresh change output, or explorer-service needs a fresh
     * receive address. Maintains privacy invariant: callers should verify that
     * the returned address also has no on-chain activity before committing to use it.
     *
     * The operation is idempotent: if an address at (walletId, type, index) already
     * exists in DB, it's returned as-is instead of re-derived.
     */
    fun deriveAdditionalAddress(
        walletId: String,
        type: String,
        index: Int
    ): WalletAddressResponse = transaction {
        require(type == "receive" || type == "change") {
            "type must be 'receive' or 'change', got: $type"
        }
        require(index >= 0) { "index must be non-negative, got: $index" }

        // Idempotent: return existing row if it's already there.
        val existing = WalletAddressesTable
            .selectAll()
            .where {
                (WalletAddressesTable.walletId eq walletId) and
                    (WalletAddressesTable.addressType eq type) and
                    (WalletAddressesTable.addressIndex eq index)
            }
            .singleOrNull()
        if (existing != null) {
            return@transaction WalletAddressResponse(
                walletId = walletId,
                address = existing[WalletAddressesTable.address],
                index = existing[WalletAddressesTable.addressIndex],
                type = existing[WalletAddressesTable.addressType]
            )
        }

        // Load wallet to get descriptor + network.
        val walletRow = WalletsTable
            .selectAll()
            .where { WalletsTable.walletId eq walletId }
            .singleOrNull()
            ?: error("Wallet not found: $walletId")

        val network = walletRow[WalletsTable.network]
        val descriptor = when (type) {
            "receive" -> walletRow[WalletsTable.receiveDescriptor]
            "change" -> walletRow[WalletsTable.changeDescriptor]
            else -> error("unreachable")
        }
        val chain = if (type == "receive") 0 else 1

        val derived = AddressDerivation.deriveAddresses(
            descriptor = descriptor,
            network = network,
            chain = chain,
            fromIndex = index,
            count = 1
        ).singleOrNull()
            ?: error("Derivation returned no address for $walletId $type/$index")

        WalletAddressesTable.insert {
            it[WalletAddressesTable.walletId] = walletId
            it[addressType] = type
            it[addressIndex] = derived.index
            it[address] = derived.address
            it[createdAt] = OffsetDateTime.now()
        }

        log.info("Derived additional {} address for wallet {}: index={} addr={}",
            type, walletId, derived.index, derived.address)

        WalletAddressResponse(
            walletId = walletId,
            address = derived.address,
            index = derived.index,
            type = type
        )
    }

    /* Returns all stored addresses for a wallet, optionally filtered by type (receive/change). */
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

    /* Returns a single address by wallet, type, and index. */
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
