package com.seedds.vplayer.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.seedds.vplayer.ui.theme.VColors

/**
 * Root shell: three bottom tabs, no icons, with the tab bar styled to match the
 * app background rather than a Material surface.
 */
@Composable
fun AppScaffold() {
    var selected by remember { mutableStateOf(TabDestination.Library) }

    Scaffold(
        containerColor = VColors.Background,
        bottomBar = {
            Column {
                HorizontalDivider(thickness = androidx.compose.ui.unit.Dp.Hairline, color = VColors.DividerTabBar)
                NavigationBar(containerColor = VColors.Background, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    TabDestination.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = {},
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = VColors.Primary,
                                unselectedTextColor = VColors.TextSecondary,
                                indicatorColor = VColors.Background,
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VColors.Background)
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = selected.label, color = VColors.TextPrimary)
        }
    }
}
