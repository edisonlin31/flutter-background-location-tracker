package com.icapps.background_location_tracker.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.huawei.hms.api.HuaweiApiAvailability
import com.icapps.background_location_tracker.flutter.FlutterBackgroundManager
import com.icapps.background_location_tracker.utils.ActivityCounter
import com.icapps.background_location_tracker.utils.Logger
import com.icapps.background_location_tracker.utils.NotificationUtil
import com.icapps.background_location_tracker.utils.SharedPrefsUtil
import java.io.PrintWriter
import java.io.StringWriter

private const val timeOut = 24 * 60 * 60 * 1000L /*24 hours max */

internal class LocationUpdatesService : Service() {
    private val binder: IBinder = LocalBinder()

    /**
     * Used to check whether the bound activity has really gone away and not unbound as part of an
     * orientation change. We create a foreground service notification only if the former takes
     * place.
     */
    private var changingConfiguration = false

    /**
     * Contains parameters used by [com.google.android.gms.location.FusedLocationProviderApi].
     */
    private var locationRequest: LocationRequest? = null
    private var huaweiLocationRequest: com.huawei.hms.location.LocationRequest? = null

    /**
     * Provides access to the Fused Location Provider API.
     */
    private var fusedLocationClient: FusedLocationProviderClient? = null

    /**
     * Callback for changes in location.
     */
    private var locationCallback: LocationCallback? = null
    private var huaweiLocationCallback: com.huawei.hms.location.LocationCallback? = null

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * The current location.
     */
    private var location: Location? = null

    override fun onCreate() {
        Logger.debug(TAG, "ON CREATE SERVICE")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                onNewLocation(locationResult.lastLocation)
            }
        }
        huaweiLocationCallback = object : com.huawei.hms.location.LocationCallback() {
            override fun onLocationResult(
                    locationResult: com.huawei.hms.location.LocationResult) {
                super.onLocationResult(locationResult)
                onNewLocation(locationResult.lastLocation)
            }
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mijnmooiestraat:location_updates")
        createLocationRequest()
        getLastLocation()

        if (SharedPrefsUtil.isTracking(this)) {
            startTracking()
        }
    }

    private fun checkGMS(): Boolean {
        val gApi = GoogleApiAvailability.getInstance()
        val resultCode = gApi.isGooglePlayServicesAvailable(this)
        return resultCode ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
    }

    private fun checkHMS(): Boolean {
        val hApi = HuaweiApiAvailability.getInstance()
        val resultCode = hApi.isHuaweiMobileServicesAvailable(this)
        return resultCode == com.huawei.hms.api.ConnectionResult.SUCCESS
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.debug(TAG, "Service started")
        val startedFromNotification = intent?.getBooleanExtra(
                EXTRA_STARTED_FROM_NOTIFICATION,
                false
        ) ?: false

        // We got here because the user decided to remove location updates from the notification.
        if (startedFromNotification) {
            stopTracking()
            stopSelf()
        }

        // Tells the system to try to recreate the service after it has been killed.
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        changingConfiguration = true
    }

    override fun onBind(intent: Intent): IBinder? {
        // Called when a client (MainActivity in case of this sample) comes to the foreground
        // and binds with this service. The service should cease to be a foreground service
        // when that happens.
        Logger.debug(TAG, "OnBind")
        stopForegroundService()
        changingConfiguration = false
        return binder
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onRebind(intent: Intent) {
        // Called when a client (MainActivity in case of this sample) returns to the foreground
        // and binds once again with this service. The service should cease to be a foreground
        // service when that happens.
        Logger.debug(TAG, "OnRebind")
        stopForegroundService()
        changingConfiguration = false
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent): Boolean {
        Logger.debug(TAG, "Last client unbound from service")

        // Called when the last client (MainActivity in case of this sample) unbinds from this
        // service. If this method is called due to a configuration change in MainActivity, we
        // do nothing. Otherwise, we make this service a foreground service.

        try {
            if (!changingConfiguration && SharedPrefsUtil.isTracking(this)) {
                Logger.debug(TAG, "Starting foreground service")
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(timeOut)
                }
                NotificationUtil.startForeground(this, location)
            }
        } catch (e: Throwable) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            pw.flush()
            Logger.error(sw.toString(), "onUnbind failed to execute");
        }

        return true // Ensures onRebind() is called when a client re-binds.
    }

    override fun onDestroy() {
        Logger.debug(TAG, "Destroy")
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    /**
     * Makes a request for location updates. Note that in this sample we merely log the
     * [SecurityException].
     */
    fun startTracking() {
        wakeLock?.acquire(timeOut)

        Logger.debug(TAG, "Requesting location updates")
        SharedPrefsUtil.saveIsTracking(this, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && ActivityCounter.isAppInBackground()) {
            startForegroundService(Intent(applicationContext, LocationUpdatesService::class.java))
            NotificationUtil.startForeground(this, location)
        } else {
            startService(Intent(applicationContext, LocationUpdatesService::class.java))
        }

        if (checkGMS()) {
            val locationRequest = locationRequest ?: return
            val locationCallback = locationCallback ?: return
            try {
                fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.myLooper())
            } catch (unlikely: SecurityException) {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                SharedPrefsUtil.saveIsTracking(this, false)
                Logger.error(TAG, "Lost location permission. Could not request updates. $unlikely")
            }
        } else if (checkHMS()) {
            val huaweiLocationRequest = huaweiLocationRequest ?: return
            val huaweiLocationCallback = huaweiLocationCallback ?: return

            try {
                com.huawei.hms.location
                        .LocationServices.getFusedLocationProviderClient(this)
                        .requestLocationUpdates(
                                huaweiLocationRequest,
                                huaweiLocationCallback,
                                Looper.getMainLooper()
                        )
            } catch (unlikely: SecurityException) {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                SharedPrefsUtil.saveIsTracking(this, false)
                Logger.error(TAG, "Lost location permission. Could not request updates. $unlikely")
            }
        }


    }

    /**
     * Removes location updates. Note that in this sample we merely log the
     * [SecurityException].
     */
    fun stopTracking() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release();
        }
        Logger.debug(TAG, "Removing location updates")
        if (checkGMS()) {
            val locationCallback = locationCallback ?: return
            try {
                fusedLocationClient?.removeLocationUpdates(locationCallback)
                SharedPrefsUtil.saveIsTracking(this, false)
                stopSelf()
            } catch (unlikely: SecurityException) {
                SharedPrefsUtil.saveIsTracking(this, true)
                Logger.error(TAG, "Lost location permission. Could not remove updates. $unlikely")
            }
        } else if (checkGMS()) {
            val huaweiLocationCallback = huaweiLocationCallback ?: return
            try {
                com.huawei.hms.location.LocationServices
                        .getFusedLocationProviderClient(this)
                        .removeLocationUpdates(huaweiLocationCallback)
                SharedPrefsUtil.saveIsTracking(this, false)
                stopSelf()
            } catch (unlikely: SecurityException) {
                SharedPrefsUtil.saveIsTracking(this, true)
                Logger.error(TAG, "Lost location permission. Could not remove updates. $unlikely")
            }
        }
    }

    private fun getLastLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    location = task.result
                } else {
                    Logger.warning(TAG, "Failed to get location.")
                }
            }
        } catch (unlikely: SecurityException) {
            Logger.error(TAG, "Lost location permission.$unlikely")
        }
    }

    private fun onNewLocation(location: Location?) {
        if (location == null) return;
        Logger.debug(TAG, "New location: $location")
        this.location = location

        if (serviceIsRunningInForeground(this)) {
            if (SharedPrefsUtil.isNotificationLocationUpdatesEnabled(applicationContext)) {
                Logger.debug(TAG, "Service is running the foreground & notification updates are enabled. So we update the notification")
                NotificationUtil.showNotification(this, location)
            }
            FlutterBackgroundManager.sendLocation(applicationContext, location)
        } else {
            val intent = Intent(ACTION_BROADCAST)
            intent.putExtra(EXTRA_LOCATION, location)
            LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
        }
    }

    /**
     * Sets the location request parameters.
     */
    private fun createLocationRequest() {
        val interval = SharedPrefsUtil.trackingInterval(this)
        val distanceFilter = SharedPrefsUtil.distanceFilter(this)
        if (checkGMS()) {
            locationRequest = LocationRequest.create()
                    .setInterval(interval)
                    .setFastestInterval(interval / 2)
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setSmallestDisplacement(distanceFilter)
        } else if (checkHMS()) {
            huaweiLocationRequest =
                    com.huawei.hms.location.LocationRequest.create()
                            .setInterval(interval)
                            .setFastestInterval(interval / 2)
                            .setPriority(com.huawei.hms.location.LocationRequest.PRIORITY_HIGH_ACCURACY)
                            .setSmallestDisplacement(distanceFilter)
        }
    }

    /**
     * Class used for the client Binder.  Since this service runs in the same process as its
     * clients, we don't need to deal with IPC.
     */
    inner class LocalBinder : Binder() {
        val service: LocationUpdatesService
            get() = this@LocationUpdatesService
    }

    /**
     * Returns true if this is a foreground service.
     *
     * @param context The [Context].
     */
    @Suppress("DEPRECATION")
    private fun serviceIsRunningInForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (javaClass.name == service.service.className) {
                if (service.foreground) {
                    return true
                }
            }
        }
        return false
    }

    companion object {
        private const val PACKAGE_NAME = "com.icapps.background_location_tracker"
        private val TAG = LocationUpdatesService::class.java.simpleName

        const val ACTION_BROADCAST = "$PACKAGE_NAME.broadcast"
        const val EXTRA_LOCATION = "$PACKAGE_NAME.location"
        const val EXTRA_STARTED_FROM_NOTIFICATION = "$PACKAGE_NAME.started_from_notification"
    }
}