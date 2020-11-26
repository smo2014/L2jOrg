/*
 * Copyright © 2019 L2J Mobius
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
package org.l2j.gameserver.model.entity;

import org.l2j.commons.database.DatabaseFactory;
import org.l2j.gameserver.cache.HtmCache;
import org.l2j.gameserver.data.sql.impl.ClanTable;
import org.l2j.gameserver.data.sql.impl.PlayerNameTable;
import org.l2j.gameserver.data.xml.impl.NpcData;
import org.l2j.gameserver.engine.item.Item;
import org.l2j.gameserver.enums.InventorySlot;
import org.l2j.gameserver.instancemanager.CastleManager;
import org.l2j.gameserver.model.StatsSet;
import org.l2j.gameserver.model.actor.instance.Player;
import org.l2j.gameserver.model.actor.templates.NpcTemplate;
import org.l2j.gameserver.network.serverpackets.InventoryUpdate;
import org.l2j.gameserver.network.serverpackets.html.NpcHtmlMessage;
import org.l2j.gameserver.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.nonNull;

/**
 * Hero entity.
 *
 * @author godson
 */
public class Hero {
    public static final String COUNT = "count";
    public static final String PLAYED = "played";
    public static final String CLAIMED = "claimed";
    public static final String CLAN_NAME = "clan_name";
    public static final String CLAN_CREST = "clan_crest";
    public static final String ALLY_NAME = "ally_name";
    public static final String ALLY_CREST = "ally_crest";
    public static final int ACTION_RAID_KILLED = 1;
    public static final int ACTION_HERO_GAINED = 2;
    public static final int ACTION_CASTLE_TAKEN = 3;
    private static final Logger LOGGER = LoggerFactory.getLogger(Hero.class);

    private static final String GET_HEROES = "SELECT heroes.charId, characters.char_name, heroes.class_id, heroes.count, heroes.played, heroes.claimed FROM heroes, characters WHERE characters.charId = heroes.charId AND heroes.played = 1";
    private static final String GET_ALL_HEROES = "SELECT heroes.charId, characters.char_name, heroes.class_id, heroes.count, heroes.played, heroes.claimed FROM heroes, characters WHERE characters.charId = heroes.charId";
    private static final String UPDATE_ALL = "UPDATE heroes SET played = 0";
    private static final String INSERT_HERO = "INSERT INTO heroes (charId, class_id, count, played, claimed) VALUES (?,?,?,?,?)";
    private static final String UPDATE_HERO = "UPDATE heroes SET count = ?, played = ?, claimed = ? WHERE charId = ?";
    private static final String GET_CLAN_ALLY = "SELECT characters.clanid AS clanid, coalesce(clan_data.ally_Id, 0) AS allyId FROM characters LEFT JOIN clan_data ON clan_data.clan_id = characters.clanid WHERE characters.charId = ?";

    // delete hero items
    private static final String DELETE_ITEMS = "DELETE FROM items WHERE item_id IN (30392, 30393, 30394, 30395, 30396, 30397, 30398, 30399, 30400, 30401, 30402, 30403, 30404, 30405, 30372, 30373, 6842, 6611, 6612, 6613, 6614, 6615, 6616, 6617, 6618, 6619, 6620, 6621, 9388, 9389, 9390) AND owner_id NOT IN (SELECT charId FROM characters WHERE accesslevel > 0)";
    private static final Map<Integer, StatsSet> HEROES = new ConcurrentHashMap<>();
    private static final Map<Integer, StatsSet> COMPLETE_HEROS = new ConcurrentHashMap<>();
    private static final Map<Integer, List<StatsSet>> HERO_DIARY = new ConcurrentHashMap<>();
    private static final Map<Integer, String> HERO_MESSAGE = new ConcurrentHashMap<>();
    public static final String CHAR_ID = "charId";
    public static final String CLASS_ID = "class_id";
    public static final String CHAR_NAME = "char_name";

    private Hero() {
    }

    private void load() {
        HEROES.clear();
        COMPLETE_HEROS.clear();
        HERO_DIARY.clear();
        HERO_MESSAGE.clear();

        try (Connection con = DatabaseFactory.getInstance().getConnection();
             Statement s1 = con.createStatement();
             ResultSet rset = s1.executeQuery(GET_HEROES);
             PreparedStatement ps = con.prepareStatement(GET_CLAN_ALLY);
             Statement s2 = con.createStatement();
             ResultSet rset2 = s2.executeQuery(GET_ALL_HEROES)) {
            while (rset.next()) {
                final StatsSet hero = new StatsSet();
                final int charId = rset.getInt(CHAR_ID);
                hero.set(CHAR_NAME, rset.getString(CHAR_NAME));
                hero.set(CLASS_ID, rset.getInt(CLASS_ID));
                hero.set(COUNT, rset.getInt(COUNT));
                hero.set(PLAYED, rset.getInt(PLAYED));
                hero.set(CLAIMED, Boolean.parseBoolean(rset.getString(CLAIMED)));

                loadDiary(charId);
                loadMessage(charId);

                processHeros(ps, charId, hero);

                HEROES.put(charId, hero);
            }

            while (rset2.next()) {
                final StatsSet hero = new StatsSet();
                final int charId = rset2.getInt(CHAR_ID);
                hero.set(CHAR_NAME, rset2.getString(CHAR_NAME));
                hero.set(CLASS_ID, rset2.getInt(CLASS_ID));
                hero.set(COUNT, rset2.getInt(COUNT));
                hero.set(PLAYED, rset2.getInt(PLAYED));
                hero.set(CLAIMED, Boolean.parseBoolean(rset2.getString(CLAIMED)));

                processHeros(ps, charId, hero);

                COMPLETE_HEROS.put(charId, hero);
            }
        } catch (SQLException e) {
            LOGGER.warn("Hero System: Couldnt load Heroes: " + e.getMessage());
        }

        LOGGER.info("Hero System: Loaded " + HEROES.size() + " Heroes.");
        LOGGER.info("Hero System: Loaded " + COMPLETE_HEROS.size() + " all time Heroes.");
    }

    private void processHeros(PreparedStatement ps, int charId, StatsSet hero) throws SQLException {
        ps.setInt(1, charId);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                final int clanId = rs.getInt("clanid");
                final int allyId = rs.getInt("allyId");
                String clanName = "";
                String allyName = "";
                int clanCrest = 0;
                int allyCrest = 0;
                if (clanId > 0) {
                    clanName = ClanTable.getInstance().getClan(clanId).getName();
                    clanCrest = ClanTable.getInstance().getClan(clanId).getCrestId();
                    if (allyId > 0) {
                        allyName = ClanTable.getInstance().getClan(clanId).getAllyName();
                        allyCrest = ClanTable.getInstance().getClan(clanId).getAllyCrestId();
                    }
                }
                hero.set(CLAN_CREST, clanCrest);
                hero.set(CLAN_NAME, clanName);
                hero.set(ALLY_CREST, allyCrest);
                hero.set(ALLY_NAME, allyName);
            }
            ps.clearParameters();
        }
    }

    /**
     * Restore hero message from Db.
     *
     * @param charId
     */
    public void loadMessage(int charId) {
        try (Connection con = DatabaseFactory.getInstance().getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT message FROM heroes WHERE charId=?")) {
            ps.setInt(1, charId);
            try (ResultSet rset = ps.executeQuery()) {
                if (rset.next()) {
                    HERO_MESSAGE.put(charId, rset.getString("message"));
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Hero System: Couldnt load Hero Message for CharId: " + charId + ": " + e.getMessage());
        }
    }

    public void loadDiary(int charId) {
        final List<StatsSet> diary = new ArrayList<>();
        int diaryentries = 0;
        try (Connection con = DatabaseFactory.getInstance().getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM  heroes_diary WHERE charId=? ORDER BY time ASC")) {
            ps.setInt(1, charId);
            try (ResultSet rset = ps.executeQuery()) {
                while (rset.next()) {
                    final StatsSet _diaryentry = new StatsSet();

                    final long time = rset.getLong("time");
                    final int action = rset.getInt("action");
                    final int param = rset.getInt("param");

                    final String date = (new SimpleDateFormat("yyyy-MM-dd HH")).format(new Date(time));
                    _diaryentry.set("date", date);

                    if (action == ACTION_RAID_KILLED) {
                        final NpcTemplate template = NpcData.getInstance().getTemplate(param);
                        if (template != null) {
                            _diaryentry.set("action", template.getName() + " was defeated");
                        }
                    } else if (action == ACTION_HERO_GAINED) {
                        _diaryentry.set("action", "Gained Hero status");
                    } else if (action == ACTION_CASTLE_TAKEN) {
                        final Castle castle = CastleManager.getInstance().getCastleById(param);
                        if (castle != null) {
                            _diaryentry.set("action", castle.getName() + " Castle was successfuly taken");
                        }
                    }
                    diary.add(_diaryentry);
                    diaryentries++;
                }
            }
            HERO_DIARY.put(charId, diary);

            LOGGER.info("Hero System: Loaded " + diaryentries + " diary entries for Hero: " + PlayerNameTable.getInstance().getNameById(charId));
        } catch (SQLException e) {
            LOGGER.warn("Hero System: Couldnt load Hero Diary for CharId: " + charId + ": " + e.getMessage());
        }
    }

    public int getHeroByClass(int classid) {
        for (Entry<Integer, StatsSet> e : HEROES.entrySet()) {
            if (e.getValue().getInt(CLASS_ID) == classid) {
                return e.getKey();
            }
        }
        return 0;
    }

    public void resetData() {
        HERO_DIARY.clear();
        HERO_MESSAGE.clear();
    }

    public void showHeroDiary(Player activeChar, int heroclass, int charid, int page) {
        final int perpage = 10;
        final List<StatsSet> mainList = HERO_DIARY.get(charid);
        if (mainList != null) {
            final NpcHtmlMessage diaryReply = new NpcHtmlMessage();
            final String htmContent = HtmCache.getInstance().getHtm(activeChar, "data/html/olympiad/herodiary.htm");
            final String heroMessage = HERO_MESSAGE.get(charid);
            if ((htmContent != null) && (heroMessage != null)) {
                diaryReply.setHtml(htmContent);
                diaryReply.replace("%heroname%", PlayerNameTable.getInstance().getNameById(charid));
                diaryReply.replace("%message%", heroMessage);
                diaryReply.disableValidation();

                if (!mainList.isEmpty()) {
                    final List<StatsSet> list = new ArrayList<>(mainList);
                    Collections.reverse(list);

                    boolean color = true;
                    final StringBuilder fList = new StringBuilder(500);
                    int counter = 0;
                    int breakat = 0;
                    for (int i = (page - 1) * perpage; i < list.size(); i++) {
                        breakat = i;
                        final StatsSet diaryEntry = list.get(i);
                        fList.append("<tr><td>");
                        if (color) {
                            fList.append("<table width=270 bgcolor=\"131210\">");
                        } else {
                            fList.append("<table width=270>");
                        }
                        fList.append("<tr><td width=270><font color=\"LEVEL\">" + diaryEntry.getString("date") + ":xx</font></td></tr>");
                        fList.append("<tr><td width=270>" + diaryEntry.getString("action") + "</td></tr>");
                        fList.append("<tr><td>&nbsp;</td></tr></table>");
                        fList.append("</td></tr>");
                        color = !color;
                        counter++;
                        if (counter >= perpage) {
                            break;
                        }
                    }

                    if (breakat < (list.size() - 1)) {
                        diaryReply.replace("%buttprev%", "<button value=\"Prev\" action=\"bypass _diary?class=" + heroclass + "&page=" + (page + 1) + "\" width=60 height=25 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\">");
                    } else {
                        diaryReply.replace("%buttprev%", "");
                    }

                    if (page > 1) {
                        diaryReply.replace("%buttnext%", "<button value=\"Next\" action=\"bypass _diary?class=" + heroclass + "&page=" + (page - 1) + "\" width=60 height=25 back=\"L2UI_ct1.button_df\" fore=\"L2UI_ct1.button_df\">");
                    } else {
                        diaryReply.replace("%buttnext%", "");
                    }

                    diaryReply.replace("%list%", fList.toString());
                } else {
                    diaryReply.replace("%list%", "");
                    diaryReply.replace("%buttprev%", "");
                    diaryReply.replace("%buttnext%", "");
                }

                activeChar.sendPacket(diaryReply);
            }
        }
    }

    public synchronized void computeNewHeroes(List<StatsSet> newHeroes) {
        updateHeroes(true);

        for (Integer objectId : HEROES.keySet()) {
            final Player player = World.getInstance().findPlayer(objectId);
            if (player == null) {
                continue;
            }

            player.setHero(false);

            for (InventorySlot slot : InventorySlot.values()) {
                var item = player.getInventory().getPaperdollItem(slot);
                if(nonNull(item) && item.isHeroItem()) {
                    player.getInventory().unEquipItemInSlot(slot);
                }
            }

            final InventoryUpdate iu = new InventoryUpdate();
            for (Item item : player.getInventory().getAvailableItems(false, false, false)) {
                if ((item != null) && item.isHeroItem()) {
                    player.destroyItem("Hero", item, null, true);
                    iu.addRemovedItem(item);
                }
            }

            if (!iu.isEmpty()) {
                player.sendInventoryUpdate(iu);
            }

            player.broadcastUserInfo();
        }

        deleteItemsInDb();

        HEROES.clear();

        if (newHeroes.isEmpty()) {
            return;
        }

        for (StatsSet hero : newHeroes) {
            final int charId = hero.getInt(CHAR_ID);

            if (COMPLETE_HEROS.containsKey(charId)) {
                final StatsSet oldHero = COMPLETE_HEROS.get(charId);
                final int count = oldHero.getInt(COUNT);
                oldHero.set(COUNT, count + 1);
                oldHero.set(PLAYED, 1);
                oldHero.set(CLAIMED, false);
                HEROES.put(charId, oldHero);
            } else {
                final StatsSet newHero = new StatsSet();
                newHero.set(CHAR_NAME, hero.getString(CHAR_NAME));
                newHero.set(CLASS_ID, hero.getInt(CLASS_ID));
                newHero.set(COUNT, 1);
                newHero.set(PLAYED, 1);
                newHero.set(CLAIMED, false);
                HEROES.put(charId, newHero);
            }
        }

        updateHeroes(false);
    }

    private void updateHeroes(boolean setDefault) {
        try (Connection con = DatabaseFactory.getInstance().getConnection()) {
            if (setDefault) {
                try (Statement s = con.createStatement()) {
                    s.executeUpdate(UPDATE_ALL);
                }
            } else {
                StatsSet hero;
                int heroId;
                for (Entry<Integer, StatsSet> entry : HEROES.entrySet()) {
                    hero = entry.getValue();
                    heroId = entry.getKey();
                    if (!COMPLETE_HEROS.containsKey(heroId)) {
                        try (PreparedStatement insert = con.prepareStatement(INSERT_HERO)) {
                            insert.setInt(1, heroId);
                            insert.setInt(2, hero.getInt(CLASS_ID));
                            insert.setInt(3, hero.getInt(COUNT));
                            insert.setInt(4, hero.getInt(PLAYED));
                            insert.setString(5, String.valueOf(hero.getBoolean(CLAIMED)));
                            insert.execute();
                        }

                        try (PreparedStatement statement = con.prepareStatement(GET_CLAN_ALLY)) {
                            statement.setInt(1, heroId);
                            try (ResultSet rset = statement.executeQuery()) {
                                if (rset.next()) {
                                    final int clanId = rset.getInt("clanid");
                                    final int allyId = rset.getInt("allyId");

                                    String clanName = "";
                                    String allyName = "";
                                    int clanCrest = 0;
                                    int allyCrest = 0;

                                    if (clanId > 0) {
                                        clanName = ClanTable.getInstance().getClan(clanId).getName();
                                        clanCrest = ClanTable.getInstance().getClan(clanId).getCrestId();

                                        if (allyId > 0) {
                                            allyName = ClanTable.getInstance().getClan(clanId).getAllyName();
                                            allyCrest = ClanTable.getInstance().getClan(clanId).getAllyCrestId();
                                        }
                                    }

                                    hero.set(CLAN_CREST, clanCrest);
                                    hero.set(CLAN_NAME, clanName);
                                    hero.set(ALLY_CREST, allyCrest);
                                    hero.set(ALLY_NAME, allyName);
                                }
                            }
                        }
                        HEROES.put(heroId, hero);

                        COMPLETE_HEROS.put(heroId, hero);
                    } else {
                        try (PreparedStatement statement = con.prepareStatement(UPDATE_HERO)) {
                            statement.setInt(1, hero.getInt(COUNT));
                            statement.setInt(2, hero.getInt(PLAYED));
                            statement.setString(3, String.valueOf(hero.getBoolean(CLAIMED)));
                            statement.setInt(4, heroId);
                            statement.execute();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Hero System: Couldnt update Heroes: " + e.getMessage());
        }
    }

    public void setRBkilled(int charId, int npcId) {
        setDiaryData(charId, ACTION_RAID_KILLED, npcId);

        final NpcTemplate template = NpcData.getInstance().getTemplate(npcId);
        final List<StatsSet> list = HERO_DIARY.get(charId);
        if ((list == null) || (template == null)) {
            return;
        }
        // Prepare new data
        final StatsSet diaryEntry = new StatsSet();
        final String date = (new SimpleDateFormat("yyyy-MM-dd HH")).format(new Date(System.currentTimeMillis()));
        diaryEntry.set("date", date);
        diaryEntry.set("action", template.getName() + " was defeated");
        // Add to old list
        list.add(diaryEntry);
    }

    public void setCastleTaken(int charId, int castleId) {
        setDiaryData(charId, ACTION_CASTLE_TAKEN, castleId);

        final Castle castle = CastleManager.getInstance().getCastleById(castleId);
        final List<StatsSet> list = HERO_DIARY.get(charId);
        if ((list == null) || (castle == null)) {
            return;
        }
        // Prepare new data
        final StatsSet diaryEntry = new StatsSet();
        final String date = (new SimpleDateFormat("yyyy-MM-dd HH")).format(new Date(System.currentTimeMillis()));
        diaryEntry.set("date", date);
        diaryEntry.set("action", castle.getName() + " Castle was successfuly taken");
        // Add to old list
        list.add(diaryEntry);
    }

    private void setDiaryData(int charId, int action, int param) {
        try (Connection con = DatabaseFactory.getInstance().getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO heroes_diary (charId, time, action, param) values(?,?,?,?)")) {
            ps.setInt(1, charId);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, action);
            ps.setInt(4, param);
            ps.execute();
        } catch (SQLException e) {
            LOGGER.error("SQL exception while saving DiaryData: " + e.getMessage());
        }
    }

    /**
     * Set new hero message for hero
     *
     * @param player  the player instance
     * @param message String to set
     */
    public void setHeroMessage(Player player, String message) {
        HERO_MESSAGE.put(player.getObjectId(), message);
    }

    /**
     * Update hero message in database
     *
     * @param charId character objid
     */
    private void saveHeroMessage(int charId) {
        if (HERO_MESSAGE.containsKey(charId)) {
            return;
        }

        try (Connection con = DatabaseFactory.getInstance().getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE heroes SET message=? WHERE charId=?;")) {
            ps.setString(1, HERO_MESSAGE.get(charId));
            ps.setInt(2, charId);
            ps.execute();
        } catch (SQLException e) {
            LOGGER.error("SQL exception while saving HeroMessage:" + e.getMessage());
        }
    }

    private void deleteItemsInDb() {
        try (Connection con = DatabaseFactory.getInstance().getConnection();
             Statement s = con.createStatement()) {
            s.executeUpdate(DELETE_ITEMS);
        } catch (SQLException e) {
            LOGGER.warn("Heroes: " + e.getMessage());
        }
    }

    /**
     * Saving task for {@link Hero}<BR>
     * Save all hero messages to DB.
     */
    public void shutdown() {
        for (int charId : HERO_MESSAGE.keySet()) {
            saveHeroMessage(charId);
        }
    }

    public static void init() {
        getInstance().load();
    }

    public static Hero getInstance() {
        return Singleton.INSTANCE;
    }

    private static class Singleton {
        private static final Hero INSTANCE = new Hero();
    }
}
