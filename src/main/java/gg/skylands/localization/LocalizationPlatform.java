package gg.skylands.localization;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface LocalizationPlatform {

    @NotNull
    <V extends Audience> String parsePlaceholderApi(@Nullable V viewer, @NotNull String text);

    @NotNull
    <V extends Audience>Component parseItemsAdder(@Nullable V viewer, @NotNull Component component);

}
