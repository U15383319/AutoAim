package dev.cosmos.autoaim;

import dev.architectury.utils.Env;
import dev.architectury.utils.EnvExecutor;
import dev.cosmos.autoaim.client.AutoAimClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AutoAim {
    public static final String MOD_ID = "autoaim";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private AutoAim() {
    }

    public static void init() {
        LOGGER.info("Initializing Auto Aim");
        EnvExecutor.runInEnv(Env.CLIENT, () -> AutoAimClient::init);
    }
}
