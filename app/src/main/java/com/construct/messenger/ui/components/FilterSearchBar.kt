package com.construct.messenger.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.construct.messenger.R

/**
 * A search field that filters the list beneath it as you type — chats, Synaps.
 *
 * Material's collapsed `SearchBar` (`docs/MATERIAL3_MIGRATION.md`, step 2): container, shape,
 * height and type come from the theme. It never expands into a search view, because there are no
 * results to show apart from the list itself; [onQueryChange] is all the screen needs. A clear
 * button appears once something is typed, as the designer's Material kit draws the trailing icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.search_prompt),
) {
    SearchBar(
        state = rememberSearchBarState(),
        modifier = modifier,
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isEmpty()) null else {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
            )
        },
    )
}

@Preview
@Composable
private fun FilterSearchBarEmptyPreview() {
    FilterSearchBar(query = "", onQueryChange = {})
}

@Preview
@Composable
private fun FilterSearchBarFilledPreview() {
    FilterSearchBar(query = "silent fox", onQueryChange = {})
}
