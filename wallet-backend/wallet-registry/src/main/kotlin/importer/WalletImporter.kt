package cz.majny.wallet.registry.importer

import cz.majny.wallet.registry.Repository
import cz.majny.wallet.registry.api.*
import org.slf4j.LoggerFactory

/* Orchestrates wallet import from an output descriptor. Steps:
 *   1. Parse descriptor (type, M/N, cosigners, script type).
 *   2. Validate that the caller's active account is actually a cosigner
 *      (for multisig) - surfaces a friendly error to the UI otherwise.
 *   3. Dedup: if a wallet with the same ID already exists, return it and
 *      auto-attach the calling device as a new member.
 *   4. Otherwise create wallet + cosigners and pre-derive addresses. */
class WalletImporter(
    private val repo: Repository
) {
    private val log = LoggerFactory.getLogger(WalletImporter::class.java)

    /* Entry point. Returns ImportResult with success=false + error text for
     * user-facing failures (bad descriptor, caller not a cosigner), throws
     * for infrastructure errors. */
    fun importWallet(request: ImportWalletRequest): ImportResult {
        log.info("Importing wallet: network={}, deviceId={}, accountIndex={}",
            request.network, request.deviceId, request.accountIndex)

        // 1. Parse the descriptor
        val parsed = try {
            DescriptorParser.parse(
                raw = request.descriptor,
                network = request.network,
                label = request.label
            )
        } catch (e: DescriptorParseException) {
            return ImportResult(
                success = false,
                error = "Descriptor parse error: ${e.message}"
            )
        }

        // 2. Validate: if this is a multisig and account index is provided,
        //    verify the active account is actually a cosigner
        if (parsed.type == "MULTI_SIG" && request.accountIndex != null) {
            val hasCosignerForAccount = parsed.cosigners.any { cosigner ->
                extractAccountIndexFromOrigin(cosigner.originPath) == request.accountIndex
            }
            if (!hasCosignerForAccount) {
                return ImportResult(
                    success = false,
                    error = "Account #${request.accountIndex + 1} is not a cosigner in this multisig wallet"
                )
            }
        }

        // 3. Check if wallet already exists
        val existing = repo.getWallet(parsed.walletId)
        if (existing != null) {
            log.info("Wallet already exists: {}", parsed.walletId)

            // Auto-attach device's active account as member (with per-member label)
            if (request.deviceId != null) {
                autoAttachDevice(parsed.walletId, request.deviceId, request.accountIndex, request.label)
            }

            return ImportResult(
                success = true,
                walletId = existing.walletId,
                isNew = false,
                wallet = existing
            )
        }

        // 4. Build the CreateWalletRequest
        val createReq = CreateWalletRequest(
            walletId = parsed.walletId,
            network = parsed.network,
            type = parsed.type,
            scriptType = parsed.scriptType,
            m = parsed.m,
            n = parsed.n,
            accountIndex = parsed.accountIndex,
            birthHeight = request.birthHeight,
            label = parsed.label,
            receiveDescriptor = parsed.receiveDescriptor,
            changeDescriptor = parsed.changeDescriptor,
            cosigners = parsed.cosigners.map { c ->
                CosignerInWallet(
                    idx = c.idx,
                    cosignerId = c.cosignerId,
                    fingerprint = c.fingerprint,
                    originPath = c.originPath,
                    xpubRoot = c.xpub
                )
            },
            members = buildMemberList(request.deviceId, request.accountIndex)
        )

        // 5. Create wallet (this also derives addresses)
        val created = try {
            repo.createWallet(createReq)
        } catch (e: Exception) {
            log.error("Failed to create wallet: {}", e.message, e)
            return ImportResult(
                success = false,
                error = "Failed to create wallet: ${e.message}"
            )
        }

        log.info("Wallet imported successfully: id={}, type={}, m={}, n={}",
            created.walletId, created.type, created.m, created.n)

        return ImportResult(
            success = true,
            walletId = created.walletId,
            isNew = true,
            wallet = created
        )
    }

    /**
     * Build the initial member list for the wallet.
     * Uses the account index directly from the import request.
     */
    private fun buildMemberList(
        deviceId: String?,
        accountIndex: Int?
    ): List<MemberAttach> {
        if (deviceId == null) return emptyList()
        return listOf(
            MemberAttach(
                deviceId = deviceId,
                accountIndex = accountIndex ?: -1
            )
        )
    }

    /**
     * Auto-attach a device's active account to an existing wallet.
     */
    private fun autoAttachDevice(
        walletId: String,
        deviceId: String,
        accountIndex: Int?,
        label: String? = null
    ) {
        try {
            repo.attachMember(
                walletId = walletId,
                deviceId = deviceId,
                accountIndex = accountIndex ?: -1,
                label = label
            )
            log.info("Auto-attached device {} to wallet {} (accountIndex={}, label={})",
                deviceId, walletId, accountIndex, label)
        } catch (e: Exception) {
            log.warn("Could not attach device {} to wallet {}: {}",
                deviceId, walletId, e.message)
        }
    }

    private fun extractAccountIndexFromOrigin(originPath: String): Int? {
        val segments = originPath.replace("'", "").replace("h", "").split("/")
        return if (segments.size >= 3) segments[2].toIntOrNull() else null
    }
}
