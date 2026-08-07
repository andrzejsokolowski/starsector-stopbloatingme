package stopbloatingme;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Entry point for StopBloatingMe.
 *
 * <p>Deliberately thin. The title-screen button is <em>not</em> registered here: it comes from an
 * {@code EveryFrameCombatPlugin} listed under {@code "plugins"} in our {@code data/config/settings.json},
 * which the engine merges into the core settings and ticks in every combat engine -- including the
 * one running behind the main menu. See {@link TitleScreenHook}.</p>
 */
public class StopBloatingMeModPlugin extends BaseModPlugin {

    public static final String MOD_ID = "stopbloatingme";

    @Override
    public void onApplicationLoad() throws Exception {
        Global.getLogger(StopBloatingMeModPlugin.class)
                .info("StopBloatingMe: application loaded.");
    }
}
