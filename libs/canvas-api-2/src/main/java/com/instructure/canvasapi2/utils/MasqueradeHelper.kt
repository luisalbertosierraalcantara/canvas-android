/*
 * Copyright (C) 2017 - present Instructure, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */
package com.instructure.canvasapi2.utils

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.webkit.CookieManager
import com.instructure.canvasapi2.CanvasRestAdapter
import com.instructure.canvasapi2.StatusCallback
import com.instructure.canvasapi2.builders.RestBuilder
import com.instructure.canvasapi2.managers.UserManager
import com.instructure.canvasapi2.models.User
import retrofit2.Call
import retrofit2.Response
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

object MasqueradeHelper {

    var masqueradeLogoutTask: Runnable? = null

    @JvmStatic
    @JvmOverloads
    fun <ACTIVITY : Activity> stopMasquerading(startingClass: Class<ACTIVITY>? = null) {
        if (ApiPrefs.isStudentView) {
            // Clear out the Teacher's token so we don't invalidate it on logout
            ApiPrefs.accessToken = ""
        }

        if ((ApiPrefs.isMasqueradingFromQRCode || ApiPrefs.isStudentView) && masqueradeLogoutTask != null) {
            masqueradeLogoutTask?.run()
            return // stopMasquerading is called again via LogoutTask, which will run the code below
        }

        ApiPrefs.masqueradeId = -1L
        ApiPrefs.domain = ""
        ApiPrefs.masqueradeDomain = ""
        ApiPrefs.masqueradeUser = null
        ApiPrefs.accessToken = ""
        ApiPrefs.clientSecret = ""
        ApiPrefs.clientId = ""
        cleanupMasquerading(ContextKeeper.appContext)
        ApiPrefs.isMasquerading = false
        ApiPrefs.isStudentView = false
        if (startingClass != null) restartApplication(startingClass)
    }

    @JvmStatic
    fun <ACTIVITY : Activity> startMasquerading(
        masqueradingUserId: Long,
        masqueradingDomain: String?,
        startingClass: Class<out ACTIVITY>,
        masqueradeToken: String = "",
        masqueradeClientId: String = ApiPrefs.clientId,
        masqueradeClientSecret: String = ApiPrefs.clientSecret,
        courseId: Long? = null) {
        // Check to see if they're trying to switch domain as site admin, or masquerading as a test student from
        // a different domain
        if (!masqueradingDomain.isNullOrBlank()) {
            // If we don't set isMasquerading to true here the original domain will be set to the masquerading domain, even if trying to
            // masquerade fails
            ApiPrefs.isMasquerading = true
            ApiPrefs.isStudentView = courseId != null

            ApiPrefs.masqueradeId = masqueradingUserId
            // Because isMasquerading is set to true this will also set the masqueradingDomain
            ApiPrefs.domain = masqueradingDomain
            ApiPrefs.accessToken = masqueradeToken
            ApiPrefs.clientId = masqueradeClientId
            ApiPrefs.clientSecret = masqueradeClientSecret
        }

        try {
            if (ApiPrefs.isStudentView) {
                ApiPrefs.isMasquerading = false // Turn this off so we don't append as_user_id when we get/create the test user account
                // Make API call to get and/or create Test User account
                UserManager.getTestUser(courseId, object : StatusCallback<User>() {
                    override fun onResponse(response: Response<User>, linkHeaders: LinkHeaders, type: ApiType) {
                        ApiPrefs.isMasquerading = true // Start adding the as_user_id url param
                        if (response.body() != null) {
                            cleanupMasquerading(ContextKeeper.appContext)
                            ApiPrefs.user = response.body()
                            ApiPrefs.masqueradeId = response.body()!!.id
                            restartApplication(startingClass)
                        }
                    }

                    override fun onFail(call: Call<User>?, error: Throwable, response: Response<*>?) {
                        Logger.e("Error failed to get test user: " + error.message)
                        stopMasquerading(startingClass)
                    }
                }, true)

            } else {
                UserManager.getUser(masqueradingUserId, object : StatusCallback<User>() {
                    override fun onResponse(response: Response<User>, linkHeaders: LinkHeaders, type: ApiType) {
                        if (response.body() != null) {
                            cleanupMasquerading(ContextKeeper.appContext)
                            // isMasquerading is already set so this will set the masqueradeUser
                            ApiPrefs.user = response.body()
                            restartApplication(startingClass)
                        }
                    }

                    override fun onFail(call: Call<User>?, error: Throwable, response: Response<*>?) {
                        Logger.e("Error failed to masquerade: " + error.message)
                        stopMasquerading(startingClass)
                    }
                }, true)
            }
        } catch (e: Exception) {
            Logger.e("Error masquerading: $e")
            stopMasquerading(startingClass)
        }
    }

    private fun <ACTIVITY : Activity> restartApplication(startingClass: Class<ACTIVITY>) {
        // Totally restart the app so the masquerading will apply
        val startupIntent = Intent(ContextKeeper.appContext, startingClass)
        startupIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val pendingIntent = PendingIntent.getActivity(ContextKeeper.appContext, 6660, startupIntent, PendingIntent.FLAG_CANCEL_CURRENT)
        val alarmManager = ContextKeeper.appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent)

        // Delays the exit long enough for all the shared preferences to be saved and caches to be cleared.
        Handler().postDelayed({
            exitProcess(0)
        }, 500)
    }

    /** Appends the masquerade ID to the provided URL (if currently masquerading) */
    @JvmStatic
    fun addMasqueradeId(url: String): String {
        if (!ApiPrefs.isMasquerading) return url
        val queryChar = if ('?' in url) '&' else '?'
        return "$url${queryChar}as_user_id=${ApiPrefs.masqueradeId}"
    }

    @Suppress("DEPRECATION")
    private fun cleanupMasquerading(context: Context) {
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookie()

        val client = CanvasRestAdapter.client
        if (client != null) {
            try {
                client.cache()?.evictAll()
            } catch (e: IOException) {/* Do Nothing */ }
        }

        val file: File = if (context.externalCacheDir != null) {
            File(context.externalCacheDir, "attachments")
        } else {
            context.filesDir
        }

        FileUtils.deleteAllFilesInDirectory(file)
        RestBuilder.clearCacheDirectory()
    }
}
