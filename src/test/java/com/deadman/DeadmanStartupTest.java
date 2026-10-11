package com.deadman;

import java.lang.reflect.Method;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.util.Filepath;
import org.junit.Test;
import static org.junit.Assert.assertNotNull;

public class DeadmanStartupTest
{
    @Test
    public void managedDataDirectoryIsAvailableWithoutHubAnnotationInjection() throws Exception
    {
        Method directory = Plugin.class.getDeclaredMethod("getPluginDirectory");
        directory.setAccessible(true);
        Filepath path = (Filepath) directory.invoke(new DeadmanPlugin());
        assertNotNull(path);
    }
}
