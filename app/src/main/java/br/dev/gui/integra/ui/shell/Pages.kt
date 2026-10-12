package br.dev.gui.integra.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import br.dev.gui.integra.ui.assistant.AssistantScreen
import br.dev.gui.integra.ui.crons.CronsScreen
import br.dev.gui.integra.ui.google.GoogleAccountsScreen
import br.dev.gui.integra.ui.permissions.PermissionsScreen
import br.dev.gui.integra.ui.servermetrics.ServerMetricsScreen
import br.dev.gui.integra.ui.statusplatform.StatusPlatformScreen
import br.dev.gui.integra.ui.theme.Hero
import br.dev.gui.integra.ui.tplink.TpLinkScreen
import br.dev.gui.integra.ui.trainstatus.TrainStatusScreen
import br.dev.gui.integra.ui.tuya.TuyaScreen

/**
 * As telas, na ordem do menu do web (`GlobalDrawerContext`). [routeKey] é a rota que a tela precisa
 * para listar algo; sem ela a tela some do menu (o web mostrava e deixava o servidor responder 403).
 */
enum class Page(val label: String, val icon: ImageVector, val routeKey: String?, val content: @Composable () -> Unit) {
    Tuya("Casa", Hero.Home, "GET /api/tuya/devices", { TuyaScreen() }),
    Assistant("Assistente", Hero.Microphone, "POST /api/assistant/chat", { AssistantScreen() }),
    ServerMetrics("Server Metrics", Hero.Server, "GET /api/server-metrics/history", { ServerMetricsScreen() }),
    TpLink("TP-Link Center", Hero.Wifi, "GET /api/tplink/devices", { TpLinkScreen() }),
    StatusPlatform("Status Platform", Hero.ShieldCheck, "GET /api/status-platform/list", { StatusPlatformScreen() }),
    TrainStatus("Train Status", Hero.Truck, "GET /api/train-status/lines", { TrainStatusScreen() }),
    Crons("Crons", Hero.ServerStack, "GET /api/crons/list", { CronsScreen() }),
    GoogleAccounts("Google Accounts", Hero.UserGroup, "GET /api/google/accounts", { GoogleAccountsScreen() }),
    Permissions("Permissões", Hero.Key, "GET /api/permissions/users", { PermissionsScreen() }),
}
