package org.schabi.newpipe.local.feed.notifications

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.NotificationMode
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.PermissionHelper

/**
 * TubeTube: one place, from the Subscriptions tab, to choose which followed channels notify
 * about new videos. Turns the app-wide switch on or off to match; the checks themselves run with
 * the background update (or, when that is off, on the notifications schedule).
 */
object NewVideoNotificationsDialog {
    fun show(activity: Activity): Disposable {
        val manager = SubscriptionManager(activity)
        return manager.subscriptions().firstOrError()
            .map { list -> list.sortedBy { it.name.orEmpty().lowercase() } }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ subscriptions ->
                if (subscriptions.isEmpty()) {
                    Toast.makeText(activity, R.string.new_video_notifications_no_channels, Toast.LENGTH_LONG).show()
                    return@subscribe
                }
                val names = subscriptions.map { it.name.orEmpty() }.toTypedArray()
                val checked = BooleanArray(subscriptions.size) {
                    subscriptions[it].notificationMode == NotificationMode.ENABLED
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(R.string.new_video_notifications_title)
                    .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        val updates = subscriptions.indices
                            .filter { (subscriptions[it].notificationMode == NotificationMode.ENABLED) != checked[it] }
                            .map {
                                manager.updateNotificationMode(
                                    subscriptions[it].serviceId,
                                    subscriptions[it].url!!,
                                    if (checked[it]) NotificationMode.ENABLED else NotificationMode.DISABLED
                                )
                            }
                        Completable.merge(updates).subscribeOn(Schedulers.io()).subscribe({}, {})
                        val anyEnabled = checked.any { it }
                        PreferenceManager.getDefaultSharedPreferences(activity).edit {
                            putBoolean(activity.getString(R.string.enable_streams_notifications), anyEnabled)
                        }
                        NotificationWorker.initialize(activity)
                        if (anyEnabled) {
                            ensurePermission(activity)
                        }
                    }
                    .setNeutralButton(R.string.new_video_notifications_all, null)
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                dialog.setOnShowListener {
                    // "all"/"none" toggles the checkboxes without closing the dialog
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        val newValue = !checked.all { it }
                        checked.fill(newValue)
                        names.indices.forEach { dialog.listView.setItemChecked(it, newValue) }
                    }
                }
                dialog.show()
            }, { error ->
                Toast.makeText(activity, error.message.orEmpty(), Toast.LENGTH_LONG).show()
            })
    }

    private fun ensurePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE
            )
        } else if (!NotificationHelper.areNotificationsEnabledOnDevice(activity)) {
            Toast.makeText(activity, R.string.new_video_notifications_blocked, Toast.LENGTH_LONG).show()
            NotificationHelper.openNewPipeSystemNotificationSettings(activity)
        }
    }
}
