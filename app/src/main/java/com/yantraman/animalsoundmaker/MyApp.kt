package com.yantraman.animalsoundmaker

import android.app.Application
import com.google.android.gms.ads.MobileAds



class MyApplication : Application() {


    lateinit var appOpenAdManager: AppOpenAdManager

    override fun onCreate() {
        super.onCreate()
        MobileAds.initialize(this@MyApplication) {}
        appOpenAdManager = AppOpenAdManager()
    }
}