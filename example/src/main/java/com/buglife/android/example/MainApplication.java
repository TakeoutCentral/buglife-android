package com.buglife.android.example;

import android.app.Application;

import com.buglife.sdk.Buglife;
import com.buglife.sdk.InvocationMethod;

public class MainApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // TODO: Replace `name@example.com` with your email to receive bug reports :)
        Buglife.initWithEmail(this, "name@example.com");
        Buglife.setInvocationMethod(InvocationMethod.SCREENSHOT);
    }
}
