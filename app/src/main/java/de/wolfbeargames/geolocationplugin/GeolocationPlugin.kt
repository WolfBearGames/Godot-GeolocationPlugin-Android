/*
Copyright 2022 Andreas Ritter (www.wolfbeargames.de)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial
portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT
OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package de.wolfbeargames.geolocationplugin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import org.godotengine.godot.Dictionary
import org.godotengine.godot.Godot
import org.godotengine.godot.plugin.GodotPlugin
import org.godotengine.godot.plugin.SignalInfo
import org.godotengine.godot.plugin.UsedByGodot

class GeolocationPlugin(godot: Godot) : GodotPlugin(godot) {

    override fun getPluginName(): String {
        return "Geolocation"
    }

    //region Enum definition
    enum class GeolocationAuthorizationStatus(val value: Int) {
        PERMISSION_STATUS_UNKNOWN(1 shl 0),
        PERMISSION_STATUS_DENIED(1 shl 1),
        PERMISSION_STATUS_ALLOWED(1 shl 2)
    }

    enum class GeolocationDesiredAccuracyConstants(val value: Int) {
        ACCURACY_BEST_FOR_NAVIGATION(1 shl 0),
        ACCURACY_BEST(1 shl 1),
        ACCURACY_NEAREST_TEN_METERS(1 shl 2),
        ACCURACY_HUNDRED_METERS(1 shl 3),
        ACCURACY_KILOMETER(1 shl 40),
        ACCURACY_THREE_KILOMETER(1 shl 5),
        ACCURACY_REDUCED(1 shl 6),
    }

    enum class GeolocationErrorCodes(val value: Int) {
        ERROR_DENIED(1 shl 0),
        ERROR_NETWORK(1 shl 1),
        ERROR_HEADING_FAILURE(1 shl 2),
        ERROR_LOCATION_UNKNOWN(1 shl 3),
        ERROR_TIMEOUT(1 shl 4),
        ERROR_UNSUPPORTED(1 shl 5),
        ERROR_LOCATION_DISABLED(1 shl 6),
        ERROR_UNKNOWN(1 shl 7),
    }

    //endregion

    //region Signal Definition
    private val _logSignal =
        SignalInfo("log", String::class.java, Float::class.javaObjectType)
    private val _errorSignal =
        SignalInfo("error", Int::class.javaObjectType)
    private val _locationUpdateSignal =
        SignalInfo("location_update", Dictionary::class.java)
    private val _authorizationChangedSignal =
        SignalInfo("authorization_changed", Int::class.javaObjectType)
    private val _headingUpdateSignal =
        SignalInfo("heading_update", Dictionary::class.java)
    private val _locationCapabiltityResult =
        SignalInfo(
            "location_capability_result",
            Boolean::class.javaObjectType
        )

    override fun getPluginSignals(): Set<SignalInfo> {
        return setOf(
            _logSignal,
            _errorSignal,
            _locationUpdateSignal,
            _authorizationChangedSignal,
            _headingUpdateSignal,
            _locationCapabiltityResult
        )
    }
    //endregion


    //region Initialization
    // references
    private var _locationCallback: LocationCallback? = null
    private var _locationOneTimeCallback: LocationCallback? = null
    private var _locationManager: FusedLocationProviderClient? = null

    private var _handler: Handler? = null
    private var _timeoutRunnable: Runnable? = null

    // state
    private var _locationUpdatesActive: Boolean = false
    private var _hasCoarsePermission: Boolean = false
    private var _hasFinePermission: Boolean = false
    private var _failureTimeoutRunning: Boolean = false
    private var _locationUpdatesPaused: Boolean = false

    // data storage
    private var _lastLocationData = Dictionary()

    // settings
    private var _distanceFilter: Float = 0f
    private var _desiredAccuracy: Int = Priority.PRIORITY_HIGH_ACCURACY
    private var _desiredUpdateInterval: Long = 1 // in seconds
    private var _maxWaitTime: Long = 1 // in seconds
    private var _returnStringCoordinates: Boolean = true
    private var _sendDebugLogSignal: Boolean = false
    private var _autoCheckLocationCapability: Boolean = false
    private var _failureTimeout: Long = 20 // also set in constructor
    private var _useFailureTimeout: Boolean = true

    // list of all supported methods
    private val _supportedMethods: Array<String> = arrayOf("authorization_status",
        "allows_full_accuracy", "can_request_permissions","is_updating_location",
        "set_distance_filter","set_desired_accuracy","set_return_string_coordinates",
        "request_location","start_updating_location","stop_updating_location",
        "request_location_capabilty", "set_debug_log_signal","set_failure_timeout",
        "should_show_permission_requirement_explanation", "set_update_interval", "set_max_wait_time",
        "set_auto_check_location_capability","should_check_location_capability")

    override fun onMainCreate(activity: Activity?): View? {
        _locationManager = LocationServices.getFusedLocationProviderClient(activity!!)

        set_failure_timeout(20)

        _handler = Handler(Looper.getMainLooper())
        _timeoutRunnable = Runnable {
            StopFailureTimeout()
            _locationManager!!.removeLocationUpdates(_locationCallback!!)
            _locationManager!!.removeLocationUpdates(_locationOneTimeCallback!!)
            _locationUpdatesActive = false
            send_error_signal(GeolocationErrorCodes.ERROR_TIMEOUT.value)
        }

        _locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (_failureTimeoutRunning) StopFailureTimeout()
                send_log_signal("m location_update UPDATE location")
                for (location in locationResult.locations) {
                    sendLocationUpdate(location)
                }
            }
        }

        _locationOneTimeCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (_failureTimeoutRunning) StopFailureTimeout()
                _locationManager!!.removeLocationUpdates(this)
                send_log_signal("m request_location ONE location")
                sendLocationUpdate(locationResult.lastLocation!!)
            }
        }

        return super.onMainCreate(activity)
    }

    private fun StartFailureTimeout() {
        _failureTimeoutRunning = true
        _handler!!.postDelayed(_timeoutRunnable!!, _failureTimeout * 1000)
    }

    private fun StopFailureTimeout() {
        _failureTimeoutRunning = false
        _handler!!.removeCallbacks(_timeoutRunnable!!)
    }


    private fun restartLocationUpdates() {
        if (_locationUpdatesActive)
        {
            send_log_signal("restart watch after settings change")
            stop_updating_location()
            start_updating_location()
        }
    }

    private fun updateAuthorizationStatus() {
        val statusFine =
            ContextCompat.checkSelfPermission(activity!!, Manifest.permission.ACCESS_FINE_LOCATION)
        val statusCoarse = ContextCompat.checkSelfPermission(
            activity!!,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        _hasCoarsePermission = (statusCoarse == PackageManager.PERMISSION_GRANTED)
        _hasFinePermission = (statusFine == PackageManager.PERMISSION_GRANTED)
    }

    private fun HasLocationPermissions(): Boolean {
        if (ContextCompat.checkSelfPermission(
                activity!!,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            != PackageManager.PERMISSION_GRANTED
        ) {
            send_error_signal(GeolocationErrorCodes.ERROR_DENIED.value)
            return false
        }
        return true
    }

    private fun HasAppropriateLocationCapability(continueWith: ((request:LocationRequest?) -> Unit)? = null) {
        val request = CreateLocationRequestWithCurrentSettings()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(request)
        val client = LocationServices.getSettingsClient(activity!!)
        val task = client.checkLocationSettings(builder.build())
        task.addOnSuccessListener { response ->
            val states = response.locationSettingsStates
            if (states!!.isLocationPresent) {
                send_log_signal("m location_capabilty result: TRUE")
                send_location_capability_signal(true)

                if (continueWith != null) continueWith(request)
            }
        }
        task.addOnFailureListener { _ ->
            send_log_signal("m location_capabilty result: FALSE")
            send_location_capability_signal(false)

            // in case we would have continued with getting a location
            // we trigger an error here to signal that there won't be location_update ever
            if (continueWith != null)
            {
                send_error_signal(GeolocationErrorCodes.ERROR_LOCATION_DISABLED.value)
            }
        }
    }

    private fun CreateLocationRequestWithCurrentSettings(): LocationRequest {
        val request = LocationRequest.create()
        request.priority = _desiredAccuracy
        request.maxWaitTime = _maxWaitTime * 1000
        request.interval = _desiredUpdateInterval * 1000
        request.smallestDisplacement = _distanceFilter
        return request
    }

    @SuppressLint("MissingPermission")
    private fun RequestLocationInternal(request :LocationRequest? = null) {
        // workaround because getCurrentLocation does not work ("No virtual method getCurrentLocation" Error):
        // request location updates and stop as soon as we got one location
        _locationManager!!.requestLocationUpdates(
            LocationRequest.create().setPriority(_desiredAccuracy).setMaxWaitTime(0),
            _locationOneTimeCallback!!,
            Looper.getMainLooper()
        ).addOnFailureListener { // don't know if this works and has any effect
            send_error_signal(GeolocationErrorCodes.ERROR_LOCATION_UNKNOWN.value)
        }

        if(_useFailureTimeout) StartFailureTimeout()
    }

    @SuppressLint("MissingPermission")
    private fun StartUpdatingLocationInternal(_request :LocationRequest? = null) {
        val request:LocationRequest = _request ?: CreateLocationRequestWithCurrentSettings()

        _locationUpdatesActive = true

        _locationManager!!.requestLocationUpdates(
            request,
            _locationCallback!!,
            Looper.getMainLooper()
        ).addOnFailureListener { // don't know if this works and has any effect
            send_error_signal(GeolocationErrorCodes.ERROR_LOCATION_UNKNOWN.value)
        }

        if(_useFailureTimeout) StartFailureTimeout()
    }

    //endregion

    //region Authorization, permission and status

    // unused on Android
    @UsedByGodot
    fun request_permission() {
        // not implemented (use Godot mechanism to ask for permissions
        send_log_signal("m request_permission NO EFFECT")
        //send_error_signal(GeolocationErrorCodes.ERROR_UNSUPPORTED.value)
    }

    @UsedByGodot
    fun authorization_status(): Int { // Enum Geolocation::GeolocationAuthorizationStatus
        send_log_signal("m authorization_status")

        updateAuthorizationStatus()

        if (_hasCoarsePermission || _hasFinePermission)
            return GeolocationAuthorizationStatus.PERMISSION_STATUS_ALLOWED.value

        return GeolocationAuthorizationStatus.PERMISSION_STATUS_DENIED.value
    }

    @UsedByGodot
    fun request_location_capabilty() {
        send_log_signal("m location_capabilty")
        HasAppropriateLocationCapability()
    }

    @UsedByGodot
    fun allows_full_accuracy(): Boolean {
        send_log_signal("m allows_full_accuracy")

        return (ContextCompat.checkSelfPermission(
            activity!!,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED)
    }

    @UsedByGodot
    fun can_request_permissions(): Boolean {
        send_log_signal("m can_request_permissions")
        updateAuthorizationStatus()
        if (!_hasCoarsePermission) return true
        if (!_hasFinePermission) return true
        return false
    }

    @UsedByGodot
    fun should_show_permission_requirement_explanation(): Boolean {
        send_log_signal("m should_show_permission_requirement_explanation")
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                activity!!,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        ) return true
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                activity!!,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) return true
        return false
    }

    @UsedByGodot
    fun should_check_location_capability(): Boolean {
        return !_autoCheckLocationCapability
    }

    @UsedByGodot
    fun is_updating_location(): Boolean {
        send_log_signal("m is_updating_location")
        return _locationUpdatesActive
    }

    @UsedByGodot
    fun is_updating_heading(): Boolean {
        send_log_signal("m is_updating_heading UNSUPPORTED")
        return false
    }

    @UsedByGodot
    fun supports(methodName:String): Boolean {
        send_log_signal("m supports: $methodName")
        if(_supportedMethods.contains(methodName)) return true
        return false
    }

    //endregion

    //region  Options
    @UsedByGodot
    fun set_update_interval(timeInSeconds: Int) { // android only!
        send_log_signal("m set_update_interval", timeInSeconds.toFloat())
        _desiredUpdateInterval = timeInSeconds.toLong();

        restartLocationUpdates()
    }

    @UsedByGodot
    fun set_max_wait_time(timeInSeconds: Int) { // android only!
        send_log_signal("m set_max_wait_time", timeInSeconds.toFloat())
        _maxWaitTime = timeInSeconds.toLong();

        restartLocationUpdates()
    }

    @UsedByGodot
    fun set_distance_filter(distanceInMeters: Float) {
        send_log_signal("m set_distance_filter", distanceInMeters)
        _distanceFilter = distanceInMeters;

        restartLocationUpdates()
    }

    @UsedByGodot
    fun set_desired_accuracy(desiredAccuracyConstant: Int) { // ENum Geolocation::GeolocationDesiredAccuracyConstants
        send_log_signal("m set_desired_accuracy", desiredAccuracyConstant.toFloat())

        when (desiredAccuracyConstant) {
            GeolocationDesiredAccuracyConstants.ACCURACY_BEST_FOR_NAVIGATION.value -> _desiredAccuracy =
                Priority.PRIORITY_HIGH_ACCURACY
            GeolocationDesiredAccuracyConstants.ACCURACY_BEST.value -> _desiredAccuracy =
                Priority.PRIORITY_HIGH_ACCURACY
            GeolocationDesiredAccuracyConstants.ACCURACY_NEAREST_TEN_METERS.value -> _desiredAccuracy =
                Priority.PRIORITY_HIGH_ACCURACY
            GeolocationDesiredAccuracyConstants.ACCURACY_HUNDRED_METERS.value -> _desiredAccuracy =
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            GeolocationDesiredAccuracyConstants.ACCURACY_KILOMETER.value -> _desiredAccuracy =
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            GeolocationDesiredAccuracyConstants.ACCURACY_THREE_KILOMETER.value -> _desiredAccuracy =
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            GeolocationDesiredAccuracyConstants.ACCURACY_REDUCED.value -> _desiredAccuracy =
                Priority.PRIORITY_LOW_POWER
        }
        restartLocationUpdates()
    }

    @UsedByGodot
    fun set_return_string_coordinates(returnStringCoordinates: Boolean) {
        _returnStringCoordinates = returnStringCoordinates
        send_log_signal("m set_return_string_coordinates $returnStringCoordinates")
    }

    @UsedByGodot
    fun set_failure_timeout(seconds: Int) {
        _failureTimeout = seconds.toLong()
        _useFailureTimeout = (seconds > 0)
        send_log_signal("m set_failure_timeout $seconds")
    }

    @UsedByGodot
    fun set_debug_log_signal(send: Boolean) {
        _sendDebugLogSignal = send
        send_log_signal("m set_debug_log_signal $send")
    }

    @UsedByGodot
    fun set_auto_check_location_capability(auto: Boolean) {
        _autoCheckLocationCapability = auto
        send_log_signal("m set_auto_check_location_capability $auto")
    }

    //endregion

    //region Location

    @SuppressLint("MissingPermission")
    @UsedByGodot
    fun request_location() {
        send_log_signal("m request_location")
        if (!HasLocationPermissions()) return

        // check GPS capability and continue to get location
        if(_autoCheckLocationCapability){
            HasAppropriateLocationCapability(this::RequestLocationInternal)
        } else {
            RequestLocationInternal()
        }
    }

    @SuppressLint("MissingPermission")
    @UsedByGodot
    fun start_updating_location() {
        send_log_signal("m start_updating_location")
        if (_locationUpdatesActive) return
        if (!HasLocationPermissions()) return

        // check GPS capability and continue to get location
        if(_autoCheckLocationCapability){
            HasAppropriateLocationCapability(this::StartUpdatingLocationInternal)
        } else {
            StartUpdatingLocationInternal()
        }
    }

    @UsedByGodot
    fun stop_updating_location() {
        send_log_signal("m stop_updating_location")
        //if (!_locationUpdatesActive) return
        _locationUpdatesActive = false
        _locationManager!!.removeLocationUpdates(_locationCallback!!)
    }
    //endregion

    //region Heading (not implemented)

    @UsedByGodot
    fun start_updating_heading() {
        send_log_signal("m start_updating_heading UNSUPPORTED")
        //send_error_signal(GeolocationErrorCodes.ERROR_HEADING_FAILURE.value)
        send_error_signal(GeolocationErrorCodes.ERROR_UNSUPPORTED.value)
        // not implemented
    }

    @UsedByGodot
    fun stop_updating_heading() {
        send_log_signal("m stop_updating_heading")
        send_error_signal(GeolocationErrorCodes.ERROR_UNSUPPORTED.value)
        // not implemented
    }
    //endregion

    //region Signal sender methods
    private fun send_log_signal(message: String, number: Float = 0f) {
        if (!_sendDebugLogSignal) return;
        emitSignal(_logSignal.name, message, number)
    }

    private fun send_error_signal(errorCode: Int) {
        send_log_signal("m send_error_signal",errorCode.toFloat())
        emitSignal(_errorSignal.name, errorCode)
    }

    private fun send_location_update_signal(locationData: Dictionary) {
        emitSignal(_locationUpdateSignal.name, locationData)
    }

    // currently not supported
    private fun send_heading_update_signal(headingData: Dictionary) {
        emitSignal(_headingUpdateSignal.name, headingData)
    }

    private fun send_location_capability_signal(capable: Boolean) {
        emitSignal(_locationCapabiltityResult.name, capable)
    }
    //endregion

    //region Response location dictionary
    private fun sendLocationUpdate(location: Location) {

        _lastLocationData["latitude"] = location.latitude
        _lastLocationData["longitude"] = location.longitude

        if (_returnStringCoordinates) {
            _lastLocationData["latitude_string"] = location.latitude.toString();
            _lastLocationData["longitude_string"] = location.longitude.toString();
        }

        _lastLocationData["accuracy"] = location.accuracy

        _lastLocationData["altitude"] = location.altitude
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            _lastLocationData["altitude_accuracy"] = location.verticalAccuracyMeters
        } else {
            _lastLocationData["altitude_accuracy"] = -1
        }

        _lastLocationData["course"] = location.bearing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            _lastLocationData["course_accuracy"] = location.bearingAccuracyDegrees
        } else {
            _lastLocationData["course_accuracy"] = -1.0
        }

        _lastLocationData["speed"] = location.speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            _lastLocationData["speed_accuracy"] = location.speedAccuracyMetersPerSecond
        } else {
            _lastLocationData["speed_accuracy"] = -1
        }
        _lastLocationData["timestamp"] = location.time / 1000 // return seconds, not milliseconds

        send_location_update_signal(_lastLocationData)
    }
    //endregion
}