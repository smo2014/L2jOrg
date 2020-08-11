/*
 * Copyright © 2019-2020 L2JOrg
 *
 * This file is part of the L2JOrg project.
 *
 * L2JOrg is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * L2JOrg is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2j.gameserver.network.clientpackets;

import org.l2j.gameserver.engine.item.shop.LCoinShop;
import org.l2j.gameserver.engine.item.shop.lcoin.LCoinShopProduct;
import org.l2j.gameserver.model.actor.instance.Player;
import org.l2j.gameserver.model.actor.request.LCoinShopRequest;
import org.l2j.gameserver.model.holders.ItemHolder;
import org.l2j.gameserver.network.serverpackets.l2coin.ExPurchaseLimitShopItemBuy;

import java.util.List;

/**
 * @author JoeAlisson
 */
public class RequestPurchaseLimitShopItemBuy extends ClientPacket {
    private int productId;
    private int amount;
    private byte tab;

    @Override
    protected void readImpl() throws Exception {
        tab = readByte();
        productId = readInt();
        amount = readInt();
    }

    @Override
    protected void runImpl() {
        final Player player = client.getPlayer();

        LCoinShopProduct product = LCoinShop.getInstance().getProductInfo(productId);
        List<ItemHolder> ingredients = product.ingredients();

        if (player.hasItemRequest() || player.hasRequest(LCoinShopRequest.class) || !hasIngredients(player, ingredients) || product.isExpired()
            || player.getLevel() < product.minLevel()) {
            player.sendPacket(ExPurchaseLimitShopItemBuy.fail(product, tab));
            return;
        }

        ItemHolder productItem = product.production();

        player.addRequest(new LCoinShopRequest(player));
        consumeIngredients(player, ingredients);
        player.addItem("LCoinShop", productItem.getId(), productItem.getCount() * amount, player, true);
        player.sendPacket(ExPurchaseLimitShopItemBuy.success(product, tab));
        player.removeRequest(LCoinShopRequest.class);
    }

    private boolean hasIngredients(Player player, List<ItemHolder> ingredients) {
        for (ItemHolder ingredient : ingredients)
            if (player.getInventory().getInventoryItemCount(ingredient.getId(), -1) < ingredient.getCount() * amount) {
                return false;
            }
        return true;
    }

    private void consumeIngredients(Player player, List<ItemHolder> ingredients) {
        for (ItemHolder ingredient : ingredients) {
            player.destroyItemByItemId("LCoinShop", ingredient.getId(), ingredient.getCount() * amount, player, true);
        }
    }
}
