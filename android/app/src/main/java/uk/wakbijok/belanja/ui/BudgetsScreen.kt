package uk.wakbijok.belanja.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import uk.wakbijok.belanja.R
import uk.wakbijok.belanja.llm.Advisor
import uk.wakbijok.belanja.llm.BrainFacts
import uk.wakbijok.belanja.parser.Categorizer
import uk.wakbijok.belanja.ui.theme.LocalHumColors

/**
 * Bajet tab (mockup screen 08): overall budget in a tinted card, optional
 * per-category budgets, pear "Simpan bajet". Empty/0 clears a category budget.
 */
@Composable
fun BudgetsScreen(
    viewModel: MainViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    val overallCents by viewModel.overallBudgetCents.collectAsStateWithLifecycle()
    val categoryBudgets by viewModel.categoryBudgets.collectAsStateWithLifecycle()
    val cycleStartDay by viewModel.cycleStartDay.collectAsStateWithLifecycle()

    var overall by rememberSaveable { mutableStateOf("") }
    var cycleDay by rememberSaveable { mutableStateOf("") }
    val perCategory = remember { mutableStateMapOf<String, String>() }
    var loaded by rememberSaveable { mutableStateOf(false) }

    // Populate once from persisted values.
    LaunchedEffect(overallCents, categoryBudgets, cycleStartDay) {
        if (!loaded) {
            overall = MainViewModel.formatRm(overallCents)
            cycleDay = cycleStartDay.toString()
            Categorizer.CATEGORIES.forEach { cat ->
                perCategory[cat] = categoryBudgets[cat]?.let { MainViewModel.formatRm(it) } ?: ""
            }
            loaded = true
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            stringResource(R.string.nav_budget),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )

        AdvisorCard()
        Spacer(Modifier.height(14.dp))

        HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                MonoLabel(stringResource(R.string.bud_overall))
                Spacer(Modifier.height(8.dp))
                RmField(
                    value = overall,
                    onValueChange = { overall = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                MonoLabel(stringResource(R.string.bud_cycle))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.bud_cycle_day),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = cycleDay,
                        onValueChange = { v ->
                            if (v.length <= 2 && v.all { it.isDigit() }) cycleDay = v
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.tnum().copy(
                            fontWeight = FontWeight.SemiBold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.width(90.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.bud_cycle_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = hum.ink60,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.by_cat),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            MonoLabel(stringResource(R.string.bud_optional))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(R.string.bud_hint),
            style = MaterialTheme.typography.bodySmall,
            color = hum.ink60,
        )
        Spacer(Modifier.height(8.dp))

        Categorizer.CATEGORIES.forEach { cat ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryIconTile(cat)
                Spacer(Modifier.width(12.dp))
                Text(
                    categoryLabel(cat),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                RmField(
                    value = perCategory[cat] ?: "",
                    onValueChange = { perCategory[cat] = it },
                    modifier = Modifier.width(120.dp),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        PushButton(
            stringResource(R.string.bud_save),
            onClick = {
                viewModel.setOverallBudgetRm(overall)
                // Unparseable/blank leaves the stored day alone, same
                // philosophy as budgets: never wipe on a bad field.
                cycleDay.toIntOrNull()?.let { viewModel.setCycleStartDay(it) }
                Categorizer.CATEGORIES.forEach { cat ->
                    viewModel.setCategoryBudgetRm(cat, perCategory[cat] ?: "")
                }
                onSaved()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Compact RM input: mono "RM" prefix, right-aligned value, decimal keyboard. */
@Composable
private fun RmField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hum = LocalHumColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        prefix = {
            Text("RM", style = MaterialTheme.typography.labelSmall, color = hum.ink40)
        },
        textStyle = MaterialTheme.typography.bodyMedium.tnum().copy(
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        ),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    )
}


/**
 * Boleh beli tak? (otak mockup v2, skrin 09). The sums render ALWAYS and come
 * from the ledger; the brain only phrases the verdict. When it is off, over
 * cap or unreachable, the local arithmetic verdict ships instead — the card
 * degrades in voice, never in correctness.
 */
@Composable
private fun AdvisorCard() {
    val hum = LocalHumColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var item by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var advice by remember { mutableStateOf<Advisor.Advice?>(null) }

    HumCard(color = hum.paper2, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            MonoLabel(stringResource(R.string.adv_label))
            Spacer(Modifier.height(10.dp))
            Row {
                OutlinedTextField(
                    value = item,
                    onValueChange = { item = it },
                    label = { Text(stringResource(R.string.adv_item)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("RM") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.width(110.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            PushButton(
                if (busy) stringResource(R.string.adv_asking) else stringResource(R.string.adv_ask),
                enabled = !busy && item.isNotBlank() &&
                    (price.replace(",", "").toDoubleOrNull() ?: 0.0) > 0,
                onClick = {
                    val cents = ((price.replace(",", "").toDoubleOrNull() ?: 0.0) * 100).toLong()
                    busy = true
                    scope.launch {
                        advice = Advisor.ask(context, item.trim(), cents)
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            advice?.let { a ->
                Spacer(Modifier.height(14.dp))
                Text(
                    a.verdict,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(3.dp))
                Text(a.lede, style = MaterialTheme.typography.bodySmall, color = hum.ink60)
                Spacer(Modifier.height(10.dp))
                // The part that can be checked — always shown, always ours.
                SumRow(stringResource(R.string.adv_left), BrainFacts.rm(a.sums.budgetLeftCents))
                SumRow(item.trim(), "− " + BrainFacts.rm(a.sums.priceCents))
                SumRow(
                    stringResource(R.string.adv_tail, a.sums.daysLeft),
                    "− " + BrainFacts.rm(a.sums.typicalTailCents),
                )
                val bal = a.sums.budgetLeftCents - a.sums.priceCents - a.sums.typicalTailCents
                SumRow(
                    stringResource(if (bal >= 0) R.string.adv_bal else R.string.adv_short),
                    BrainFacts.rm(if (bal >= 0) bal else -bal),
                    strong = true,
                    color = if (bal >= 0) hum.ink else hum.coral,
                )
                if (!a.fromBrain) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.adv_local_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = hum.ink40,
                    )
                }
            }
        }
    }
}

@Composable
private fun SumRow(
    label: String,
    value: String,
    strong: Boolean = false,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val hum = LocalHumColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = hum.ink60,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (strong) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = color,
        )
    }
}
