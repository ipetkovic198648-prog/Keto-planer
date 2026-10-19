package com.keto.planer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject

data class Ingredient(val name: String, val amount: String)

data class Recipe(
    val id: Int,
    val title: String,
    val category: String,
    val kcal: Int,
    val fat: Int,
    val protein: Int,
    val carbs: Int,
    val prepTime: String,
    val imageUrl: String,
    val tags: List<String>,
    val ingredients: List<Ingredient>,
    val instructions: String
)

data class DayPlan(
    val dayName: String,
    val breakfastId: Int,
    val lunchId: Int,
    val dinnerId: Int,
    val snackId: Int,
    val breadId: Int
)

data class ShoppingItem(
    val name: String,
    val amount: String,
    val checked: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF10B981),
                    onPrimary = Color.Black,
                    secondary = Color(0xFFF59E0B),
                    background = Color(0xFF111827),
                    surface = Color(0xFF1F2937),
                    onSurface = Color(0xFFF9FAFB),
                    surfaceVariant = Color(0xFF374151)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KetoPlanerApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KetoPlanerApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("keto_prefs_v1", Context.MODE_PRIVATE) }

    val allRecipes = remember { getAllKetoRecipes() }

    // Isključeni tagovi - po difoltu su isključeni avokado i termički obrađeno povrće
    var excludedTags by remember {
        val saved = prefs.getStringSet("excluded_tags", null)
        mutableStateOf(saved?.toSet() ?: setOf("avokado", "kuvano_povrce"))
    }

    var excludedWords by remember {
        val saved = prefs.getStringSet("excluded_words", emptySet()) ?: emptySet()
        mutableStateOf(saved.toSet())
    }

    fun saveFilters(tags: Set<String>, words: Set<String>) {
        excludedTags = tags
        excludedWords = words
        prefs.edit()
            .putStringSet("excluded_tags", tags)
            .putStringSet("excluded_words", words)
            .apply()
    }

    // Filtrirani recepti na osnovu onoga što korisnik ne voli
    val allowedRecipes = remember(excludedTags, excludedWords) {
        allRecipes.filter { recipe ->
            val hasForbiddenTag = recipe.tags.any { it in excludedTags }
            val hasForbiddenWord = excludedWords.any { word ->
                word.isNotBlank() && (
                    recipe.title.contains(word, ignoreCase = true) ||
                    recipe.ingredients.any { it.name.contains(word, ignoreCase = true) }
                )
            }
            !hasForbiddenTag && !hasForbiddenWord
        }
    }

    var weeklyPlan by remember {
        mutableStateOf(loadWeeklyPlan(prefs, allowedRecipes))
    }

    var shoppingList by remember {
        mutableStateOf(loadShoppingList(prefs))
    }

    fun updateWeeklyPlan(newPlan: List<DayPlan>) {
        weeklyPlan = newPlan
        saveWeeklyPlan(prefs, newPlan)
    }

    fun updateShoppingList(newList: List<ShoppingItem>) {
        shoppingList = newList
        saveShoppingList(prefs, newList)
    }

    // Ako se promene filteri, automatski zameni zabranjene obroke u planu dozvoljenim
    LaunchedEffect(allowedRecipes) {
        val allowedIds = allowedRecipes.map { it.id }.toSet()
        var changed = false
        val updated = weeklyPlan.map { day ->
            val b = if (day.breakfastId in allowedIds) day.breakfastId else pickRandomId(allowedRecipes, "Doručak")
            val l = if (day.lunchId in allowedIds) day.lunchId else pickRandomId(allowedRecipes, "Glavno jelo")
            val d = if (day.dinnerId in allowedIds) day.dinnerId else pickRandomId(allowedRecipes, "Glavno jelo", excludeId = l)
            val s = if (day.snackId in allowedIds) day.snackId else pickRandomSnackOrSweetId(allowedRecipes)
            val br = if (day.breadId in allowedIds) day.breadId else pickRandomId(allowedRecipes, "Keto hleb")
            if (b != day.breakfastId || l != day.lunchId || d != day.dinnerId || s != day.snackId || br != day.breadId) {
                changed = true
            }
            day.copy(breakfastId = b, lunchId = l, dinnerId = d, snackId = s, breadId = br)
        }
        if (changed) updateWeeklyPlan(updated)
    }

    var currentTab by remember { mutableStateOf(0) }
    var selectedRecipeForDetail by remember { mutableStateOf<Recipe?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Keto Planer & Recepti", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("Planer") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.RestaurantMenu, contentDescription = null) },
                    label = { Text("Recepti (${allowedRecipes.size})") }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = {
                        BadgedBox(badge = {
                            val unchecked = shoppingList.count { !it.checked }
                            if (unchecked > 0) {
                                Badge { Text("$unchecked") }
                            }
                        }) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null)
                        }
                    },
                    label = { Text("Kupovina") }
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Ne volim") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (currentTab) {
                0 -> PlannerScreen(
                    weeklyPlan = weeklyPlan,
                    allRecipes = allRecipes,
                    allowedRecipes = allowedRecipes,
                    onRegenerateWeek = {
                        updateWeeklyPlan(generateNewWeekPlan(allowedRecipes))
                    },
                    onUpdateDay = { index, updatedDay ->
                        val mutable = weeklyPlan.toMutableList()
                        mutable[index] = updatedDay
                        updateWeeklyPlan(mutable)
                    },
                    onAddDayToShopping = { dayPlan ->
                        val newItems = buildShoppingFromPlans(listOf(dayPlan), allRecipes, shoppingList)
                        updateShoppingList(newItems)
                        currentTab = 2
                    },
                    onAddWeekToShopping = {
                        val newItems = buildShoppingFromPlans(weeklyPlan, allRecipes, shoppingList)
                        updateShoppingList(newItems)
                        currentTab = 2
                    },
                    onRecipeClick = { selectedRecipeForDetail = it }
                )
                1 -> RecipesScreen(
                    recipes = allowedRecipes,
                    totalCount = allRecipes.size,
                    onRecipeClick = { selectedRecipeForDetail = it }
                )
                2 -> ShoppingListScreen(
                    items = shoppingList,
                    onUpdateItems = { updateShoppingList(it) },
                    onGenerateFromWeek = {
                        updateShoppingList(buildShoppingFromPlans(weeklyPlan, allRecipes, shoppingList))
                    }
                )
                3 -> FiltersScreen(
                    excludedTags = excludedTags,
                    excludedWords = excludedWords,
                    allowedCount = allowedRecipes.size,
                    totalCount = allRecipes.size,
                    onUpdateFilters = { tags, words -> saveFilters(tags, words) }
                )
            }
        }
    }

    selectedRecipeForDetail?.let { recipe ->
        RecipeDetailDialog(
            recipe = recipe,
            onDismiss = { selectedRecipeForDetail = null },
            onAddIngredientsToCart = {
                val updated = shoppingList.toMutableList()
                recipe.ingredients.forEach { ing ->
                    updated.add(ShoppingItem(ing.name, "${ing.amount} (${recipe.title})", false))
                }
                updateShoppingList(updated)
                selectedRecipeForDetail = null
                currentTab = 2
            }
        )
    }
}

@Composable
fun PlannerScreen(
    weeklyPlan: List<DayPlan>,
    allRecipes: List<Recipe>,
    allowedRecipes: List<Recipe>,
    onRegenerateWeek: () -> Unit,
    onUpdateDay: (Int, DayPlan) -> Unit,
    onAddDayToShopping: (DayPlan) -> Unit,
    onAddWeekToShopping: () -> Unit,
    onRecipeClick: (Recipe) -> Unit
) {
    var isWeeklyView by remember { mutableStateOf(false) }
    var selectedDayIndex by remember { mutableStateOf(0) }
    val recipeMap = remember(allRecipes) { allRecipes.associateBy { it.id } }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Prekidač Dnevni / Nedeljni plan + akcije
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(4.dp)
            ) {
                Button(
                    onClick = { isWeeklyView = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (!isWeeklyView) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (!isWeeklyView) Color.Black else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Dnevni plan", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { isWeeklyView = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isWeeklyView) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isWeeklyView) Color.Black else Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Nedeljni pregled", fontWeight = FontWeight.Bold)
                }
            }

            IconButton(
                onClick = onRegenerateWeek,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Novi plan", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(10.dp))

        if (!isWeeklyView) {
            // Izbor dana u nedelji
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                weeklyPlan.forEachIndexed { index, day ->
                    val selected = index == selectedDayIndex
                    FilterChip(
                        selected = selected,
                        onClick = { selectedDayIndex = index },
                        label = { Text(day.dayName, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val currentDay = weeklyPlan.getOrNull(selectedDayIndex) ?: return@Column
            val bRecipe = recipeMap[currentDay.breakfastId]
            val lRecipe = recipeMap[currentDay.lunchId]
            val dRecipe = recipeMap[currentDay.dinnerId]
            val sRecipe = recipeMap[currentDay.snackId]
            val brRecipe = recipeMap[currentDay.breadId]

            val dayRecipes = listOfNotNull(bRecipe, lRecipe, dRecipe, sRecipe, brRecipe)
            val totalKcal = dayRecipes.sumOf { it.kcal }
            val totalFat = dayRecipes.sumOf { it.fat }
            val totalProt = dayRecipes.sumOf { it.protein }
            val totalCarbs = dayRecipes.sumOf { it.carbs }

            // Kartica sa dnevnim makrosima
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${currentDay.dayName} — Ukupno sa hlebom:",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { onAddDayToShopping(currentDay) }) {
                            Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Dodaj dan u korpu", fontSize = 12.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        MacroPill("Kalorije", "$totalKcal kcal", Color(0xFF10B981))
                        MacroPill("Masti", "${totalFat}g", Color(0xFFF59E0B))
                        MacroPill("Proteini", "${totalProt}g", Color(0xFF3B82F6))
                        MacroPill("Neto UH", "${totalCarbs}g", Color(0xFFEC4899))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                bRecipe?.let {
                    item {
                        MealPlanCard(
                            mealLabel = "DORUČAK",
                            recipe = it,
                            onClick = { onRecipeClick(it) },
                            onSwap = {
                                val newId = pickRandomId(allowedRecipes, "Doručak", it.id)
                                onUpdateDay(selectedDayIndex, currentDay.copy(breakfastId = newId))
                            }
                        )
                    }
                }
                lRecipe?.let {
                    item {
                        MealPlanCard(
                            mealLabel = "RUČAK",
                            recipe = it,
                            onClick = { onRecipeClick(it) },
                            onSwap = {
                                val newId = pickRandomId(allowedRecipes, "Glavno jelo", it.id)
                                onUpdateDay(selectedDayIndex, currentDay.copy(lunchId = newId))
                            }
                        )
                    }
                }
                dRecipe?.let {
                    item {
                        MealPlanCard(
                            mealLabel = "VEČERA",
                            recipe = it,
                            onClick = { onRecipeClick(it) },
                            onSwap = {
                                val newId = pickRandomId(allowedRecipes, "Glavno jelo", it.id)
                                onUpdateDay(selectedDayIndex, currentDay.copy(dinnerId = newId))
                            }
                        )
                    }
                }
                sRecipe?.let {
                    item {
                        MealPlanCard(
                            mealLabel = "UŽINA / SLATKIŠ",
                            recipe = it,
                            onClick = { onRecipeClick(it) },
                            onSwap = {
                                val newId = pickRandomSnackOrSweetId(allowedRecipes, it.id)
                                onUpdateDay(selectedDayIndex, currentDay.copy(snackId = newId))
                            }
                        )
                    }
                }
                brRecipe?.let {
                    item {
                        MealPlanCard(
          
