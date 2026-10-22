package pl.autorynek.data;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.TreeMap;

/**
 * "Odcisk" przedmiotu: minecraft id + wlasna nazwa + enchanty.
 * Przedmiot z rynku jest uznany za taki sam, gdy wszystkie trzy sie zgadzaja.
 */
public record ItemKey(String id, String name, Map<String, Integer> enchants) {

    public static ItemKey of(ItemStack stack) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        String name = custom == null ? null : custom.getString();
        Map<String, Integer> enchants = new TreeMap<>();
        collect(stack.get(DataComponentTypes.ENCHANTMENTS), enchants);
        collect(stack.get(DataComponentTypes.STORED_ENCHANTMENTS), enchants);
        return new ItemKey(id, name, enchants);
    }

    private static void collect(ItemEnchantmentsComponent component, Map<String, Integer> out) {
        if (component == null) return;
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : component.getEnchantmentEntries()) {
            int level = e.getIntValue();
            e.getKey().getKey().ifPresent(k -> out.put(k.getValue().toString(), level));
        }
    }

    public boolean matches(ItemStack stack) {
        return equals(of(stack));
    }
}
