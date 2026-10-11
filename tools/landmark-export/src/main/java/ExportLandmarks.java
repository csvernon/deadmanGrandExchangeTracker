import com.google.gson.Gson;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.cache.AreaManager;
import net.runelite.cache.WorldMapManager;
import net.runelite.cache.definitions.AreaDefinition;
import net.runelite.cache.definitions.WorldMapElementDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Offline development tool; never loaded by the RuneLite plugin. */
public final class ExportLandmarks
{
    private static final Logger log = LoggerFactory.getLogger(ExportLandmarks.class);

    public static void main(String[] args) throws Exception
    {
        if (args.length != 1)
        {
            throw new IllegalArgumentException("Supply the path to a copied game cache directory");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        try (Store cache = new Store(new File(args[0])))
        {
            cache.load();
            AreaManager labels = new AreaManager(cache);
            labels.load();
            WorldMapManager map = new WorldMapManager(cache);
            map.load();
            for (WorldMapElementDefinition element : map.getElements())
            {
                AreaDefinition label = labels.getArea(element.getAreaDefinitionId());
                if (label == null || label.name == null || label.name.trim().length() < 3)
                {
                    continue;
                }
                Position tile = element.getWorldPosition();
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("label", label.name.trim());
                record.put("x", tile.getX());
                record.put("y", tile.getY());
                record.put("plane", tile.getZ());
                record.put("labelSize", label.textScale);
                if (!records.contains(record))
                {
                    records.add(record);
                }
            }
        }
        records.sort(Comparator.comparing(record -> (String) record.get("label")));
        log.info("LANDMARK_DATA {}", new Gson().toJson(records));
    }
}
