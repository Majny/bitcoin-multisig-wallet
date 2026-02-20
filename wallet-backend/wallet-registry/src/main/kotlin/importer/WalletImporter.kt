package cz.majny.wallet.registry.importer

import cz.majny.wallet.registry.RegistryRepository
import cz.majny.wallet.registry.api.*
import org.slf4j.LoggerFactory

/**
 * Wallet Importer — orchestrates the import of external wallet configurations.
 *
 * Supports importing from:
 *   - Raw output descriptor strings (from Sparrow, Bitcoin Core, etc.)
 *   - In future: BSMS JSON, Specter exports
 *
 * Flow:
 *   1. Parse descriptor → extract type, M/N, cosigners, script type
 *   2. BIP-67 sort cosigners (for sortedmulti)
 *   3. Validate: M ≤ N, valid xpubs, valid derivation paths
 *   4. Check for existing wallet (dedup by descriptor)
 *   5. Create wallet + cosigners in wallet-registry DB
 *   6. Auto-attach calling device if its fingerprint matches a cosigner
 *   7. Derive addresses (receive + change)
 */
class WalletImporter(
    private val repo: RegistryRepository
) {
    private val log = LoggerFactory.getLogger(WalletImporter::class.java)

    /**
     * Import a wallet from a descriptor string.
     *
     * @param request The import request containing descriptor, network, device info
     * @return [ImportResult] with the created/existing wallet details
     */
    fun importWallet(request: ImportWalletRequest): ImportResult {
        log.info("Importing wallet: network={}, deviceId={}", request.network, request.deviceId)

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

        // 2. Check if wallet already exists
        val existing = repo.getWallet(parsed.walletId)
        if (existing != null) {
            log.info("Wallet already exists: {}", parsed.walletId)

            // Auto-attach device if not already a member
            if (request.deviceId != null) {
                autoAttachDevice(parsed, request.deviceId, request.deviceFingerprint)
            }

            return ImportResult(
                success = true,
                walletId = existing.walletId,
                isNew = false,
                wallet = existing
            )
        }

        // 3. Build the CreateWalletRequest
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
            members = buildMemberList(parsed, request.deviceId, request.deviceFingerprint)
        )

        // 4. Create wallet (this also derives addresses)
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
     * If the calling device's fingerprint matches a cosigner, attach it automatically.
     */
    private fun buildMemberList(
        parsed: ParsedDescriptor,
        deviceId: String?,
        deviceFingerprint: String?
    ): List<MemberAttach> {
        if (deviceId == null) return emptyList()

        val matchingCosigner = if (deviceFingerprint != null) {
            parsed.cosigners.firstOrNull { it.fingerprint == deviceFingerprint.lowercase() }
        } else {
            null
        }

        return listOf(
            MemberAttach(
                deviceId = deviceId,
                cosignerIdx = matchingCosigner?.idx
            )
        )
    }

    /**
     * Auto-attach a device to an existing wallet if its fingerprint matches a cosigner.
     */
    private fun autoAttachDevice(
        parsed: ParsedDescriptor,
        deviceId: String,
        deviceFingerprint: String?
    ) {
        val matchingCosigner = if (deviceFingerprint != null) {
            parsed.cosigners.firstOrNull { it.fingerprint == deviceFingerprint.lowercase() }
        } else {
            null
        }

        try {
            repo.attachMember(
                walletId = parsed.walletId,
                deviceId = deviceId,
                cosignerIdx = matchingCosigner?.idx
            )
            log.info("Auto-attached device {} to wallet {} (cosigner idx={})",
                deviceId, parsed.walletId, matchingCosigner?.idx)
        } catch (e: Exception) {
            log.warn("Could not attach device {} to wallet {}: {}",
                deviceId, parsed.walletId, e.message)
        }
    }
}
