package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Tailcfg

@Composable
fun SearchAndFilter(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onlineOnly: Boolean,
    onOnlineOnlyChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
    ) {
        // Search box
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(stringResource(R.string.search_devices)) },
            leadingIcon = { 
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search"
                )
            },
            singleLine = true
        )
        
        // Online only toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.online_only),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = onlineOnly,
                onCheckedChange = onOnlineOnlyChange
            )
        }
    }
}

fun filterPeers(
    peers: List<Tailcfg.Node>,
    searchQuery: String,
    onlineOnly: Boolean
): List<Tailcfg.Node> {
    return peers.filter { peer ->
        val matchesSearch = peer.Hostinfo.Hostname?.contains(searchQuery, ignoreCase = true) ?: true
        val matchesOnline = if (onlineOnly) (peer.Online ?: false) else true
        matchesSearch && matchesOnline
    }
}