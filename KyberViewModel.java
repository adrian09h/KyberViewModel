package com.wallet.crypto.trustapp.viewmodel;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.content.Context;
import android.text.TextUtils;

import com.wallet.crypto.trustapp.C;
import com.wallet.crypto.trustapp.entity.IdentifiedSymbol;
import com.wallet.crypto.trustapp.entity.KyberCurrencyResponse;
import com.wallet.crypto.trustapp.entity.GetGasPriceResponse;
import com.wallet.crypto.trustapp.entity.KyberSourceToken;
import com.wallet.crypto.trustapp.entity.KyberValueHolder;
import com.wallet.crypto.trustapp.entity.Token;
import com.wallet.crypto.trustapp.entity.TokenInfo;
import com.wallet.crypto.trustapp.entity.Wallet;
import com.wallet.crypto.trustapp.interact.FetchTokensInteract;
import com.wallet.crypto.trustapp.interact.FindDefaultNetworkInteract;
import com.wallet.crypto.trustapp.interact.GetDefaultWalletBalance;
import com.wallet.crypto.trustapp.interact.KyberInteract;
import com.wallet.crypto.trustapp.util.LogPrinter;
import com.wallet.crypto.trustapp.util.WalletUtils;

import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

public class KyberViewModel extends GetBalanceViewModel {

    private final KyberInteract kyberInteract;

    private final MutableLiveData<ArrayList<KyberSourceToken>> sourceTokenList = new MutableLiveData<>();
    private final MutableLiveData<ArrayList<KyberCurrencyResponse.Currency>> currencyList = new MutableLiveData<>();

    private final MutableLiveData<GetGasPriceResponse> gasPrice = new MutableLiveData<>();
    private final MutableLiveData<List<Uint256>> expectedRate = new MutableLiveData<>();

    private final MutableLiveData<BigInteger> tradeGasAmount = new MutableLiveData<>();
    private final MutableLiveData<BigInteger> approveGasAmount = new MutableLiveData<>();
    private final MutableLiveData<String> approveTxHash = new MutableLiveData<>();

    private final MutableLiveData<String> tradeTxHash = new MutableLiveData<>();

    private Disposable currencyDispose;
    private Disposable filterDispose;
    private Disposable gasPriceDispose;
    private Disposable expectedRateIntervalDispose;
    private Disposable expectedRateDispose;

    KyberViewModel(
            FindDefaultNetworkInteract findDefaultNetworkInteract,
            GetDefaultWalletBalance getDefaultWalletBalance,
            FetchTokensInteract fetchTokensInteract,
            KyberInteract kyberInteract) {
        super(findDefaultNetworkInteract, fetchTokensInteract, getDefaultWalletBalance);
        this.kyberInteract = kyberInteract;
    }

    public LiveData<ArrayList<KyberSourceToken>> sourceTokenList() {
        return sourceTokenList;
    }

    public LiveData<ArrayList<KyberCurrencyResponse.Currency>> currencyList() {
        return currencyList;
    }

    public LiveData<GetGasPriceResponse> gasPrice() {
        return gasPrice;
    }

    public MutableLiveData<List<Uint256>> expectedRate() {
        return expectedRate;
    }

    @Override
    protected void onCleared() {
        dispose(currencyDispose);
        dispose(filterDispose);
        dispose(gasPriceDispose);
        disposeExpectedRate();
        super.onCleared();
    }

    public void getKyberInfo(Context context) {
        progress.setValue(true);

        getCurrencyList(context);
    }

    private void getCurrencyList(Context context) {
        currencyDispose = kyberInteract.getCurrencyList(context).subscribe(this::onGetCurrencyList, this::onError);
    }

    private void onGetCurrencyList(List<KyberCurrencyResponse.Currency> currencyList) {
        this.currencyList.postValue(new ArrayList<>(currencyList));
    }

    public MutableLiveData<String> approveTxHash() {
        return approveTxHash;
    }

    public MutableLiveData<BigInteger> approveGasAmount() {
        return approveGasAmount;
    }

    public MutableLiveData<BigInteger> tradeGasAmount() {
        return tradeGasAmount;
    }

    public void prepare() {
        progress.postValue(true);
        findDefaultNetwork();
        getGasPrice();
    }

    private void getGasPrice() {
        gasPriceDispose = kyberInteract.getGasPrice().subscribe(this::onGetGasPrice, this::onError);
    }

    private void onGetGasPrice(GetGasPriceResponse response) {
        gasPrice.postValue(response);
    }

    public synchronized void createTokenList() {
        ArrayList<KyberCurrencyResponse.Currency> currencies = currencyList.getValue();
        Token[] tokens = this.tokens.getValue();
        Map<IdentifiedSymbol, String> balances = this.balances.getValue();
        if (currencies == null || tokens == null || balances == null) {
            return;
        }
        ArrayList<KyberSourceToken> list = new ArrayList<>();
        TokenInfo info = new TokenInfo(C.ETH_ADDRESS_KYBER, C.ETHEREUM_NETWORK_NAME, C.ETH_SYMBOL, 18, C.TokenState.SHOW);
        String balanceStr = balances.get(IdentifiedSymbol.getEthSymbol());
        BigDecimal balance = TextUtils.isEmpty(balanceStr) ? BigDecimal.ZERO : new BigDecimal(balanceStr).multiply(new BigDecimal(Math.pow(10, 18)));
        list.add(new KyberSourceToken(info, balance));
        for (Token token: tokens) {
            list.add(new KyberSourceToken(token));
        }
        filterDispose = Observable.fromIterable(list).filter(kyberSourceToken -> {
            for (KyberCurrencyResponse.Currency currency: currencies) {
                if (currency.getAddress().equals(kyberSourceToken.tokenInfo.address)) {
                    kyberSourceToken.setDiffInfo(currency.getDiffInfo());
                    return true;
                }
            }
            return false;
        }).toList().subscribe(kyberHeldTokens -> {
            sourceTokenList.postValue(new ArrayList<>(kyberHeldTokens));
            progress.postValue(false);
        }, this::onError);
    }

    public synchronized void getExpectedRate(KyberValueHolder valueHolder) {
        disposeExpectedRate();
        expectedRateIntervalDispose = Observable.interval(0, C.GET_EXPECTED_RATE_INTERVAL, TimeUnit.SECONDS)
                .doOnNext(l -> {
                    expectedRateDispose = kyberInteract.getExpectedRate(
                            wallet.getValue(),
                            valueHolder.getSelectedToken().tokenInfo,
                            valueHolder.getSelectedCurrency().getAddress())
                            .subscribe(expectedRate::postValue, this::onError);
                        })
                .subscribe();
    }

    public void disposeExpectedRate() {
        dispose(expectedRateIntervalDispose);
        dispose(expectedRateDispose);
    }

    public void estimateGas(KyberValueHolder valueHolder) {
        progress.setValue(true);
        if (!C.ETH_ADDRESS_KYBER.equalsIgnoreCase(valueHolder.getSelectedToken().tokenInfo.address)) {
            confirmAllowance(wallet.getValue(), valueHolder);
        } else {
            estimateTradeGas(wallet.getValue(), valueHolder);
        }
    }

    private void confirmAllowance(Wallet wallet, KyberValueHolder valueHolder) {
        disposable = kyberInteract.confirmAllowance(wallet, valueHolder.getSelectedToken().tokenInfo)
                .subscribe(allowance -> {
                    LogPrinter.d("allowance = " + allowance.toString());
                    if (WalletUtils.convertDecimalIntoTokenValue(valueHolder.getSwapValue(), valueHolder.getSelectedToken().tokenInfo.decimals).compareTo(allowance) > 0) {
                        // swap value is more than allowance
                        estimateApproveGas(wallet, valueHolder);
                    } else {
                        estimateTradeGas(wallet, valueHolder);
                    }
                }, this::onError);
    }

    private void estimateApproveGas(Wallet wallet, KyberValueHolder valueHolder) {
        progress.setValue(true);
        disposable = kyberInteract.estimateApproveGas(wallet, valueHolder.getSelectedToken().tokenInfo, valueHolder.getSwapValue(), valueHolder.getAdvancedValue().getGasPrice())
                .subscribe(approveGas -> {
                    LogPrinter.d("approve gas amount = " + approveGas);
                    progress.postValue(false);
                    // value with a margin
                    BigInteger useValue = new BigDecimal(approveGas).multiply(BigDecimal.valueOf(C.GAS_LIMIT_RATIO_KYBER)).toBigInteger();
                    approveGasAmount.postValue(useValue);
                }, this::onError);
    }

    public void estimateTradeGas(Wallet wallet, KyberValueHolder valueHolder) {
        progress.setValue(true);
        disposable = kyberInteract.estimateTradeGas(wallet,
                valueHolder.getSelectedToken().tokenInfo,
                valueHolder.getSelectedCurrency(),
                valueHolder.getSwapValue(),
                valueHolder.exchangeRate(),
                valueHolder.getAdvancedValue().getMinAcceptableRateDecimal(),
                valueHolder.getAdvancedValue().getGasPrice())
                .subscribe(tradeGas -> {
                    LogPrinter.d("trade gas amount = " + tradeGas);
                    progress.postValue(false);
                    // value with a margin
                    BigInteger useValue = new BigDecimal(tradeGas).multiply(BigDecimal.valueOf(C.GAS_LIMIT_RATIO_KYBER)).toBigInteger();
                    tradeGasAmount.postValue(useValue);
                }, this::onError);
    }

    public void approve(Wallet wallet, KyberValueHolder valueHolder, BigInteger gasLimit) {
        progress.setValue(true);
        disposable = kyberInteract.approve(wallet, valueHolder.getSelectedToken().tokenInfo, valueHolder.getSwapValue(), valueHolder.getAdvancedValue().getGasPrice(), gasLimit)
                .subscribe(txHash -> {
                    LogPrinter.d("approve txHash = " + txHash);
                    approveTxHash.postValue(txHash);
                }, this::onError);
    }

    public MutableLiveData<String> tradeTxHash() {
        return tradeTxHash;
    }

    public void broadcast(Wallet wallet, KyberValueHolder valueHolder, BigInteger gasLimit) {
        progress.setValue(true);
        trade(wallet, valueHolder, gasLimit);
    }

    private void trade(Wallet wallet, KyberValueHolder valueHolder, BigInteger gasLimit) {
        disposable = kyberInteract.trade(wallet,
                valueHolder.getSelectedToken().tokenInfo,
                valueHolder.getSelectedCurrency(),
                valueHolder.getSwapValue(),
                valueHolder.exchangeRate(),
                valueHolder.getAdvancedValue().getMinAcceptableRateDecimal(),
                valueHolder.getAdvancedValue().getGasPrice(),
                gasLimit)
                .subscribe(txHash -> {
                    LogPrinter.d("trade txHash = " + txHash);
                    progress.postValue(false);
                    tradeTxHash.postValue(txHash);
                }, this::onError);
    }
}
