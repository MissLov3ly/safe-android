package pm.gnosis.heimdall.data.repositories.impls

import io.reactivex.Completable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import pm.gnosis.crypto.utils.Sha3Utils
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.ethereum.*
import pm.gnosis.heimdall.ERC20Contract
import pm.gnosis.heimdall.GnosisSafe
import pm.gnosis.heimdall.data.db.ApplicationDb
import pm.gnosis.heimdall.data.db.models.TransactionDescriptionDb
import pm.gnosis.heimdall.data.db.models.TransactionPublishStatusDb
import pm.gnosis.heimdall.data.remote.RelayServiceApi
import pm.gnosis.heimdall.data.remote.models.EstimateParams
import pm.gnosis.heimdall.data.remote.models.ExecuteParams
import pm.gnosis.heimdall.data.remote.models.push.ServiceSignature
import pm.gnosis.heimdall.data.repositories.PushServiceRepository
import pm.gnosis.heimdall.data.repositories.TransactionExecutionRepository
import pm.gnosis.heimdall.data.repositories.TransactionExecutionRepository.PublishStatus
import pm.gnosis.heimdall.data.repositories.TransactionExecutionRepository.TransactionSubmittedCallback
import pm.gnosis.heimdall.data.repositories.models.ERC20Token
import pm.gnosis.heimdall.data.repositories.models.SafeTransaction
import pm.gnosis.heimdall.data.repositories.toInt
import pm.gnosis.model.Solidity
import pm.gnosis.models.Transaction
import pm.gnosis.svalinn.accounts.base.models.Signature
import pm.gnosis.svalinn.accounts.base.repositories.AccountsRepository
import pm.gnosis.utils.*
import java.math.BigInteger
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultTransactionExecutionRepository @Inject constructor(
    appDb: ApplicationDb,
    private val accountsRepository: AccountsRepository,
    private val ethereumRepository: EthereumRepository,
    private val pushServiceRepository: PushServiceRepository,
    private val relayServiceApi: RelayServiceApi
) : TransactionExecutionRepository {

    private val descriptionsDao = appDb.descriptionsDao()

    private val transactionSubmittedCallbacks = CopyOnWriteArraySet<TransactionSubmittedCallback>()
    private val nonceCache = ConcurrentHashMap<Solidity.Address, BigInteger>()

    override fun addTransactionSubmittedCallback(callback: TransactionSubmittedCallback): Boolean =
        transactionSubmittedCallbacks.add(callback)

    override fun removeTransactionSubmittedCallback(callback: TransactionSubmittedCallback): Boolean =
        transactionSubmittedCallbacks.remove(callback)

    override fun calculateHash(
        safeAddress: Solidity.Address, transaction: SafeTransaction,
        txGas: BigInteger, dataGas: BigInteger, gasPrice: BigInteger, gasToken: Solidity.Address
    ): Single<ByteArray> =
        Single.fromCallable {
            val tx = transaction.wrapped
            val to = tx.address.value.paddedHexString()
            val value = tx.value?.value.paddedHexString()
            val data = Sha3Utils.keccak(tx.data?.hexToByteArray() ?: ByteArray(0)).toHex().padStart(64, '0')
            val operationString = transaction.operation.toInt().toBigInteger().paddedHexString()
            val gasPriceString = gasPrice.paddedHexString()
            val txGasString = txGas.paddedHexString()
            val dataGasString = dataGas.paddedHexString()
            val gasTokenString = gasToken.value.paddedHexString()
            val refundReceiverString = BigInteger.ZERO.paddedHexString()
            val nonce = (tx.nonce ?: DEFAULT_NONCE).paddedHexString()
            hash(
                safeAddress,
                to,
                value,
                data,
                operationString,
                txGasString,
                dataGasString,
                gasPriceString,
                gasTokenString,
                refundReceiverString,
                nonce
            )
        }.subscribeOn(Schedulers.computation())

    private fun BigInteger?.paddedHexString(padding: Int = 64): String {
        return (this?.toString(16) ?: "").padStart(padding, '0')
    }

    private fun domainHash(safeAddress: Solidity.Address) =
        Sha3Utils.keccak(
            ("0x035aff83d86937d35b32e04f0ddc6ff469290eef2f1b692d8a815c89404d4749" +
                    safeAddress.value.paddedHexString()).hexToByteArray()
        ).toHex()

    private fun valuesHash(parts: Array<out String>) =
        parts.fold(StringBuilder().append("0x14d461bc7412367e924637b363c7bf29b8f47e2f84869f4426e5633d8af47b20")) { acc, part ->
            acc.append(part)
        }.toString().run {
            Sha3Utils.keccak(hexToByteArray()).toHex()
        }

    private fun hash(safeAddress: Solidity.Address, vararg parts: String): ByteArray {
        val initial = StringBuilder().append(ERC191_BYTE).append(ERC191_VERSION).append(domainHash(safeAddress)).append(valuesHash(parts))
        return Sha3Utils.keccak(initial.toString().hexToByteArray())
    }

    private fun loadSafeState(safeAddress: Solidity.Address, paymentToken: Solidity.Address) =
        Single.fromCallable {
            TransactionInfoRequest(
                EthCall(
                    transaction = Transaction(
                        safeAddress, data = GnosisSafe.GetThreshold.encode()
                    ), id = 0
                ).toMappedRequest(),
                EthCall(
                    transaction = Transaction(
                        safeAddress, data = GnosisSafe.Nonce.encode()
                    ), id = 1
                ).toMappedRequest(),
                EthCall(
                    transaction = Transaction(
                        safeAddress, data = GnosisSafe.GetOwners.encode()
                    ), id = 2
                ).toMappedRequest(),
                balanceRequest(safeAddress, paymentToken, 3)
            )

        }.subscribeOn(Schedulers.computation())
            .flatMap { ethereumRepository.request(it).singleOrError() }

    private fun balanceRequest(safe: Solidity.Address, token: Solidity.Address, index: Int) =
        if (token == ERC20Token.ETHER_TOKEN.address) {
            MappedRequest(EthBalance(safe, id = index)) {
                it?.value
            }
        } else {
            MappedRequest(
                EthCall(
                    transaction = Transaction(
                        token,
                        data = ERC20Contract.BalanceOf.encode(safe)
                    ),
                    id = index
                )
            ) { ERC20Contract.BalanceOf.decode(it!!).balance.value }
        }

    private fun checkNonce(safeAddress: Solidity.Address, contractNonce: BigInteger): BigInteger {
        val cachedNonce = nonceCache[safeAddress]
        return if (cachedNonce != null && cachedNonce >= contractNonce) cachedNonce + BigInteger.ONE else contractNonce
    }

    override fun loadSafeExecuteState(
        safeAddress: Solidity.Address,
        paymentToken: Solidity.Address
    ): Single<TransactionExecutionRepository.SafeExecuteState> =
        loadSafeState(safeAddress, paymentToken)
            .flatMap { info -> accountsRepository.loadActiveAccount().map { info to it.address } }
            .map { (info, sender) ->
                val nonce = checkNonce(safeAddress, GnosisSafe.Nonce.decode(info.nonce.mapped()!!).param0.value)
                val threshold = GnosisSafe.GetThreshold.decode(info.threshold.mapped()!!).param0.value.toInt()
                val owners = GnosisSafe.GetOwners.decode(info.owners.mapped()!!).param0.items
                val safeBalance = info.balance.mapped()!!
                TransactionExecutionRepository.SafeExecuteState(
                    sender,
                    threshold,
                    owners,
                    nonce,
                    safeBalance
                )
            }

    override fun loadExecuteInformation(
        safeAddress: Solidity.Address,
        paymentToken: Solidity.Address,
        transaction: SafeTransaction
    ): Single<TransactionExecutionRepository.ExecuteInformation> =
        loadSafeState(safeAddress, paymentToken)
            .flatMap { info ->
                relayServiceApi.estimate(
                    safeAddress.asEthereumAddressChecksumString(),
                    EstimateParams(
                        transaction.wrapped.address.asEthereumAddressChecksumString(),
                        transaction.wrapped.value?.value?.asDecimalString() ?: "0",
                        transaction.wrapped.data ?: "0x",
                        transaction.operation.toInt(),
                        GnosisSafe.GetThreshold.decode(info.threshold.mapped()!!).param0.value.toInt(),
                        paymentToken
                    )
                ).map { info to it }
            }
            .flatMap { info -> accountsRepository.loadActiveAccount().map { info to it.address } }
            .flatMap { (infoWithEstimate, sender) ->
                val (info, estimate) = infoWithEstimate
                assert(paymentToken == estimate.gasToken)
                // We have 3 nonce sources: RPC endpoint, Estimate endpoint, local nonce cache ... we take the maximum of all
                val rpcNonce = GnosisSafe.Nonce.decode(info.nonce.mapped()!!).param0.value
                val estimateNonce = estimate.lastUsedNonce?.decimalAsBigInteger()?.let { it + BigInteger.ONE } ?: BigInteger.ZERO
                val nonce = checkNonce(safeAddress, if (rpcNonce > estimateNonce) rpcNonce else estimateNonce)

                val threshold = GnosisSafe.GetThreshold.decode(info.threshold.mapped()!!).param0.value.toInt()
                val owners = GnosisSafe.GetOwners.decode(info.owners.mapped()!!).param0.items
                val updatedTransaction = transaction.copy(wrapped = transaction.wrapped.updateTransactionWithStatus(nonce))
                val txGas = estimate.safeTxGas.decimalAsBigInteger()
                val dataGas = estimate.dataGas.decimalAsBigInteger()
                val operationalGas = estimate.operationalGas.decimalAsBigInteger()
                val gasPrice = estimate.gasPrice.decimalAsBigInteger()
                val safeBalance = info.balance.mapped()!!
                calculateHash(safeAddress, updatedTransaction, txGas, dataGas, gasPrice, paymentToken).map {
                    TransactionExecutionRepository.ExecuteInformation(
                        it.toHexString().addHexPrefix(),
                        updatedTransaction,
                        sender,
                        threshold,
                        owners,
                        paymentToken,
                        gasPrice,
                        txGas,
                        dataGas,
                        operationalGas,
                        safeBalance
                    )
                }
            }

    private fun Transaction.updateTransactionWithStatus(safeNonce: BigInteger) = nonce?.let { this } ?: copy(nonce = safeNonce)

    override fun signConfirmation(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address
    ): Single<Signature> =
        calculateHash(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken)
            .flatMap(accountsRepository::sign)

    override fun signRejection(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address
    ): Single<Signature> =
        calculateHash(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken)
            .flatMap(pushServiceRepository::calculateRejectionHash)
            .flatMap(accountsRepository::sign)

    override fun checkConfirmation(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address,
        signature: Signature
    ): Single<Pair<Solidity.Address, Signature>> =
        calculateHash(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken)
            .flatMap { accountsRepository.recover(it, signature) }
            .map { it to signature }

    override fun checkRejection(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address,
        signature: Signature
    ): Single<Pair<Solidity.Address, Signature>> =
        calculateHash(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken)
            .flatMap(pushServiceRepository::calculateRejectionHash)
            .flatMap {
                accountsRepository.recover(it, signature)
            }.map { it to signature }

    override fun notifyReject(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address,
        targets: Set<Solidity.Address>
    ): Completable =
        signRejection(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken)
            .flatMap { signature ->
                calculateHash(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken).map { it.toHexString() to signature }
            }
            .flatMapCompletable { (hash, signature) -> pushServiceRepository.propagateTransactionRejected(hash, signature, targets) }

    override fun submit(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        signatures: Map<Solidity.Address, Signature>,
        senderIsOwner: Boolean,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address,
        addToHistory: Boolean
    ): Single<String> =
        loadExecutionParams(safeAddress, transaction, signatures, senderIsOwner, txGas, dataGas, gasPrice, gasToken)
            .flatMap { relayServiceApi.execute(safeAddress.asEthereumAddressChecksumString(), it) }
            .flatMap {
                transaction.wrapped.nonce?.let { nonceCache[safeAddress] = it }
                broadcastTransactionSubmitted(safeAddress, transaction, it.transactionHash)
                if (addToHistory)
                    handleSubmittedTransaction(
                        safeAddress, transaction, it.transactionHash.addHexPrefix(), txGas, dataGas, gasPrice, gasToken
                    )
                else
                    Single.just(it.transactionHash.addHexPrefix())
            }

    private fun broadcastTransactionSubmitted(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        chainHash: String
    ) {
        transactionSubmittedCallbacks.forEach { it.onTransactionSubmitted(safeAddress, transaction, chainHash) }
    }

    private fun loadExecutionParams(
        safeAddress: Solidity.Address,
        innerTransaction: SafeTransaction,
        signatures: Map<Solidity.Address, Signature>,
        senderIsOwner: Boolean,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address
    ): Single<ExecuteParams> =
        loadSignatures(safeAddress, innerTransaction, signatures, senderIsOwner, txGas, dataGas, gasPrice, gasToken)
            .map { finalSignatures ->
                val sortedAddresses = finalSignatures.keys.map { it.value }.sorted()
                val serviceSignatures = mutableListOf<ServiceSignature>()
                sortedAddresses.forEach {
                    finalSignatures[Solidity.Address(it)]?.let {
                        serviceSignatures += ServiceSignature(it.r, it.s, it.v.toInt())
                    }
                }

                val tx = innerTransaction.wrapped
                ExecuteParams(
                    tx.address.asEthereumAddressChecksumString(),
                    tx.value?.value?.asDecimalString() ?: "0",
                    tx.data,
                    innerTransaction.operation.toInt(),
                    serviceSignatures,
                    txGas.asDecimalString(),
                    dataGas.asDecimalString(),
                    gasPrice.asDecimalString(),
                    gasToken.asEthereumAddressChecksumString(),
                    tx.nonce?.toLong() ?: 0
                )
            }

    private fun loadSignatures(
        safeAddress: Solidity.Address,
        innerTransaction: SafeTransaction,
        signatures: Map<Solidity.Address, Signature>,
        senderIsOwner: Boolean,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address
    ): Single<Map<Solidity.Address, Signature>> =
    // If owner is signature we need to sign the hash and add the signature to the map
        if (senderIsOwner)
            accountsRepository.loadActiveAccount()
                .flatMap { account ->
                    calculateHash(safeAddress, innerTransaction, txGas, dataGas, gasPrice, gasToken)
                        .flatMap { accountsRepository.sign(it) }
                        .map { signatures.plus(account.address to it) }
                }
        else Single.just(signatures)

    private fun handleSubmittedTransaction(
        safeAddress: Solidity.Address,
        transaction: SafeTransaction,
        txChainHash: String,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address
    ) =
        calculateHash(safeAddress, transaction, txGas, dataGas, gasPrice, gasToken)
            .map {
                val tx = transaction.wrapped
                val transactionUuid = UUID.randomUUID().toString()
                val transactionObject = TransactionDescriptionDb(
                    transactionUuid,
                    safeAddress,
                    tx.address,
                    tx.value?.value ?: BigInteger.ZERO,
                    tx.data ?: "",
                    transaction.operation.toInt().toBigInteger(),
                    txGas,
                    dataGas,
                    gasToken,
                    gasPrice,
                    tx.nonce ?: DEFAULT_NONCE,
                    System.currentTimeMillis(),
                    it.toHexString()
                )
                descriptionsDao.insert(transactionObject, TransactionPublishStatusDb(transactionUuid, txChainHash, null, null))
                txChainHash
            }

    override fun observePublishStatus(id: String): Observable<PublishStatus> =
        descriptionsDao.observeStatus(id)
            .toObservable()
            .switchMap { status ->
                status.success?.let {
                    Observable.just(it to (status.timestamp ?: 0))
                } ?: observeTransactionStatus(status.transactionId.hexAsBigInteger())
                    .map {
                        it.apply {
                            descriptionsDao.update(status.apply {
                                success = first
                                timestamp = second
                            })
                        }
                    }
            }
            .map { (success, timestamp) ->
                if (success) PublishStatus.Success(timestamp) else PublishStatus.Failed(timestamp)
            }
            .startWith(PublishStatus.Pending)
            .onErrorReturnItem(PublishStatus.Unknown)

    override fun observeTransactionStatus(transactionHash: BigInteger): Observable<Pair<Boolean, Long>> =
        ethereumRepository.getTransactionReceipt(transactionHash.asTransactionHash())
            .flatMap { receipt ->
                ethereumRepository.getBlockByHash(receipt.blockHash)
                    .map { receipt to (it.timestamp.toLong() * 1000) }
            }
            .retryWhen {
                it.delay(20, TimeUnit.SECONDS)
            }
            .map { (receipt, time) ->
                val executed = if (receipt.status == BigInteger.ZERO) false
                else {
                    // If we have a failure event then the transaction failed
                    receipt.logs.none {
                        it.topics.getOrNull(0) == GnosisSafe.Events.ExecutionFailed.EVENT_ID
                    }
                }
                executed to time
            }

    private class TransactionInfoRequest(
        val threshold: MappedRequest<String, String?>,
        val nonce: MappedRequest<String, String?>,
        val owners: MappedRequest<String, String?>,
        val balance: MappedRequest<out Any, BigInteger?>
    ) : MappingBulkRequest<Any?>(threshold, nonce, owners, balance)

    private fun EthRequest<String>.toMappedRequest() = MappedRequest(this) { it }

    companion object {
        private const val ERC191_BYTE = "19"
        private const val ERC191_VERSION = "01"
        private val DEFAULT_NONCE = BigInteger.ZERO
    }
}
