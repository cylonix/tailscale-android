// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
// __CYLONIX_MOD__ Removed BuildConfig.APPLICATION_ID dependency: BuildConfig
// gets APPLICATION_ID only with the application gradle plugin, but cylonix
// builds tailscale-android as a library AAR. Use the app's runtime package
// name (passed in by the constructor) instead.

data class InstalledApp(val name: String, val packageName: String)

class InstalledAppsManager(
    val packageManager: PackageManager,
    val selfPackageName: String, // __CYLONIX_ADD__
) {
  fun fetchInstalledApps(): List<InstalledApp> {
    return packageManager
        .getInstalledApplications(PackageManager.GET_META_DATA)
        .filter(appIsIncluded)
        .map {
          InstalledApp(
              name = it.loadLabel(packageManager).toString(),
              packageName = it.packageName,
          )
        }
        .sortedBy { it.name }
  }

  private val appIsIncluded: (ApplicationInfo) -> Boolean = { app ->
    app.packageName != selfPackageName && // __CYLONIX_MOD__ was BuildConfig.APPLICATION_ID
        // Only show apps that can access the Internet
        packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) ==
            PackageManager.PERMISSION_GRANTED
  }
}
