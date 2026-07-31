package com.example.esp_wifi_demo.ui.wifiaware;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class WifiAwareViewModel extends ViewModel {

    private final MutableLiveData<String> mText;

    public WifiAwareViewModel() {
        mText = new MutableLiveData<>();
        mText.setValue("This is Wi-Fi Aware fragment");
    }

    public LiveData<String> getText() {
        return mText;
    }
}
