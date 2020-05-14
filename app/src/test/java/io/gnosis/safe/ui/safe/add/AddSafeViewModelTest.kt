package io.gnosis.safe.ui.safe.add

import io.gnosis.data.repositories.SafeRepository
import io.gnosis.safe.TestLifecycleRule
import io.gnosis.safe.TestLiveDataObserver
import io.gnosis.safe.appDispatchers
import io.gnosis.safe.di.Repositories
import io.gnosis.safe.ui.base.BaseStateViewModel
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import pm.gnosis.utils.asEthereumAddress

class AddSafeViewModelTest {

    @get:Rule
    val instantExecutorRule = TestLifecycleRule()

    private val safeRepository = mockk<SafeRepository>()
    private val repositories = mockk<Repositories>().apply {
        every { safeRepository() } returns safeRepository
    }

    private lateinit var viewModel: AddSafeViewModel

    @Before
    fun setup() {
        viewModel = AddSafeViewModel(repositories, appDispatchers)
        Dispatchers.setMain(TestCoroutineDispatcher())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submitAddress (empty address string) should ShowError with InvalidSafeAddress`() {
        val address = ""
        val stateObserver = TestLiveDataObserver<BaseStateViewModel.State>()

        viewModel.submitAddress(address)

        viewModel.state.observeForever(stateObserver)
        with(stateObserver.values()[0]) {
            assert(
                viewAction is BaseStateViewModel.ViewAction.ShowError &&
                        (viewAction as BaseStateViewModel.ViewAction.ShowError).error is InvalidSafeAddress
            )
        }

        coVerify { safeRepository wasNot Called }
    }

    @Test
    fun `submitAddress (address safeRepository failure) should ShowError`() {
        val address = VALID_SAFE_ADDRESS
        val exception = IllegalStateException()
        coEvery { safeRepository.isSafeAddressUsed(address.asEthereumAddress()!!) } returns false
        coEvery { safeRepository.isValidSafe(address.asEthereumAddress()!!) } throws exception
        val stateObserver = TestLiveDataObserver<BaseStateViewModel.State>()

        viewModel.submitAddress(address)

        viewModel.state.observeForever(stateObserver)
        stateObserver
            .assertValues(
                AddSafeState(BaseStateViewModel.ViewAction.ShowError(exception))
            )
        coVerifySequence {
            safeRepository.isSafeAddressUsed(VALID_SAFE_ADDRESS.asEthereumAddress()!!)
            safeRepository.isValidSafe(VALID_SAFE_ADDRESS.asEthereumAddress()!!)
        }
    }

    @Test
    fun `submitAddress (invalid address safeRepository works) should ShowError InvalidSafeAddress`() {
        val address = "0x0"
        coEvery { safeRepository.isValidSafe(address.asEthereumAddress()!!) } returns false
        coEvery { safeRepository.isSafeAddressUsed(address.asEthereumAddress()!!) } returns false
        val stateObserver = TestLiveDataObserver<BaseStateViewModel.State>()

        viewModel.submitAddress(address)

        viewModel.state.observeForever(stateObserver)
        with(stateObserver.values()[0]) {
            assert(
                viewAction is BaseStateViewModel.ViewAction.ShowError &&
                        (viewAction as BaseStateViewModel.ViewAction.ShowError).error is InvalidSafeAddress
            )
        }
        coVerify {
            safeRepository.isSafeAddressUsed("0x0".asEthereumAddress()!!)
            safeRepository.isValidSafe("0x0".asEthereumAddress()!!)
        }
    }

    @Test
    fun `submitAddress (valid unused address) should NavigateTo`() {
        val address = VALID_SAFE_ADDRESS
        coEvery { safeRepository.isValidSafe(address.asEthereumAddress()!!) } returns true
        coEvery { safeRepository.isSafeAddressUsed(address.asEthereumAddress()!!) } returns false
        val stateObserver = TestLiveDataObserver<BaseStateViewModel.State>()

        viewModel.submitAddress(address)

        viewModel.state.observeForever(stateObserver)
        stateObserver
            .assertValues(
                AddSafeState(
                    BaseStateViewModel.ViewAction.NavigateTo(
                        AddSafeFragmentDirections.actionAddSafeFragmentToAddSafeNameFragment(address)
                    )
                )
            )

        coVerifySequence {
            safeRepository.isSafeAddressUsed(VALID_SAFE_ADDRESS.asEthereumAddress()!!)
            safeRepository.isValidSafe(VALID_SAFE_ADDRESS.asEthereumAddress()!!)
        }
    }

    @Test
    fun `submitAddress (valid used address) should ShowError UsedSafeAddress `() {
        val address = VALID_SAFE_ADDRESS
        coEvery { safeRepository.isValidSafe(address.asEthereumAddress()!!) } returns true
        coEvery { safeRepository.isSafeAddressUsed(address.asEthereumAddress()!!) } returns true
        val stateObserver = TestLiveDataObserver<BaseStateViewModel.State>()

        viewModel.submitAddress(address)

        viewModel.state.observeForever(stateObserver)
        stateObserver.assertValues(AddSafeState(BaseStateViewModel.ViewAction.ShowError(UsedSafeAddress)))

        coVerifySequence {
            safeRepository.isSafeAddressUsed(VALID_SAFE_ADDRESS.asEthereumAddress()!!)
            safeRepository.isValidSafe(VALID_SAFE_ADDRESS.asEthereumAddress()!!) wasNot Called
        }
    }

    companion object {
        private const val VALID_SAFE_ADDRESS = "0xA7e15e2e76Ab469F8681b576cFF168F37Aa246EC"
    }
}
