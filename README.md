# KyberViewModel

KyberViewModel is a viewmodel class for Kyber Swap screen of a wallet app.
Kyber Network Crystal (KNC) https://kyber.network is a platform that enables to swap between ERC-20 Tokens.

## Why interesting?

If someone would review the code lines, then he can easily notice that it uses RxJava, and it's itself ViewModel. 
And it's using Dagger 2 dependency injection framwork. In other words, it uses modern Android development technologies.
Some other libraries and technologies are used in the project.
The ViewModel is well-structured to store and manage UI-related data in a lifecycle conscious way.
Here are some examples how the viewmodel class was designed and worked.

- Prepare to get ready to make it work.
   https://github.com/adrian09h/KyberViewModel/blob/master/KyberViewModel.java#L118
   
- Used Interact class instance to interacts with model,in detail, KyberInteract class interacts with persistance store and has a Service class instance to manages REST api calls and Web3 api calls.

```
private final KyberInteract kyberInteract;
```

```
public class KyberInteract {
    private final KyberService kyberService;
    private final PasswordStore passwordStore;
}
```

- MutableLiveData holds data that is necessary to be observable in View class.

```
private final MutableLiveData<GetGasPriceResponse> gasPrice = new MutableLiveData<>();
```

- Get exchange rate at a certain interval.
   https://github.com/adrian09h/KyberViewModel/blob/master/KyberViewModel.java#L161

```
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
```


