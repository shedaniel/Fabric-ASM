/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package me.shedaniel.mm.testing;

import net.minecraft.block.entity.BannerPattern;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.item.Items;
import net.minecraft.text.ClickEvent.Action;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import me.shedaniel.mm.api.ClassTinkerers;

import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
	public static final Logger LOGGER = LogManager.getLogger();

	@Override
	public void onInitialize() {
		LOGGER.info("Math.max(5, 6) = " + Math.max(5, 6));

		BannerPattern pattern = ClassTinkerers.getEnum(BannerPattern.class, "TEST_PATTERN");
		LOGGER.info("Banner pattern: " + pattern + " with ordinal " + pattern.ordinal());

		for (BannerPattern banner : BannerPattern.values()) {
			LOGGER.debug(banner.ordinal() + " => " + banner);
		}

		LOGGER.info("Generic BannerPattern interfaces: " + Arrays.toString(BannerPattern.class.getGenericInterfaces()));

		Action action = Action.valueOf("TEST_COMMAND");
		LOGGER.info("Test click action: " + action + " with ordinal " + action.ordinal());

		for (TestEnum test : TestEnum.values()) {
			LOGGER.debug(test.ordinal() + " => " + test);
			if (test.getDeclaringClass() != test.getClass()) test.reallyMagicMethod(10);
		}

		EnchantmentTarget target = ClassTinkerers.getEnum(EnchantmentTarget.class, "CAKE");
		LOGGER.info("Can enchant cake? " + target.isAcceptableItem(Items.CAKE));
	}
}