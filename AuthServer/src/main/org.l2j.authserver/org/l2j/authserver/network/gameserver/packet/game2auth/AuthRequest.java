/*
 * Copyright © 2019-2021 L2JOrg
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
package org.l2j.authserver.network.gameserver.packet.game2auth;

import org.l2j.authserver.controller.GameServerManager;
import org.l2j.authserver.data.database.dao.GameserverDAO;
import org.l2j.authserver.network.GameServerInfo;
import org.l2j.authserver.network.gameserver.ServerClientState;
import org.l2j.authserver.network.gameserver.packet.auth2game.AuthResponse;
import org.l2j.authserver.settings.AuthServerSettings;

import static java.util.Objects.nonNull;
import static org.l2j.authserver.network.gameserver.packet.auth2game.GameServerAuthFail.*;
import static org.l2j.authserver.settings.AuthServerSettings.acceptNewGameServerEnabled;
import static org.l2j.commons.database.DatabaseAccess.getDAO;

public class AuthRequest extends GameserverReadablePacket {

	private int desiredId;
    private boolean acceptAlternativeId;
	private int maxPlayers;
	private String[] hosts;
	private int serverType;
    private byte ageLimit;
    private boolean showBrackets;
    private boolean isPvp;
    private short[] ports;
    private String authKey;

    @Override
	protected void readImpl() {
		desiredId = readByte();
        authKey = readSizedString();
        acceptAlternativeId = readBoolean();
        serverType = readInt();
        maxPlayers = readInt();
        ageLimit = readByte();
        showBrackets = readBoolean();
        isPvp = readBoolean();

        hosts = new String[readByte() * 2];
        for (int i = 0; i < hosts.length; i+=2) {
            hosts[i] =  readString();
            hosts[i+1] = readString();
        }

        var portsSize = readByte();
        ports = new short[portsSize];
        for (byte i = 0; i < portsSize; i++) {
            ports[i] = readShort();
        }
    }

	@Override
	protected void runImpl()  {
        if(!AuthServerSettings.acceptKey(authKey)) {
            client.close(FailReason.NOT_AUTHED);
            return;
        }
        GameServerManager gameServerManager = GameServerManager.getInstance();
        GameServerInfo gsi = gameServerManager.getRegisteredGameServerById(desiredId);

        if (nonNull(gsi)) {
            authenticGameServer(gsi);
        } else {
            gsi = processNewGameServer(gameServerManager);
        }

        if(nonNull(gsi) && gsi.isAuthed()) {
            client.sendPacket(new AuthResponse(gsi.getId()));
        }
	}

    private void authenticGameServer(GameServerInfo gsi) {
        if (gsi.isAuthed()) {
            client.close(FailReason.REASON_ALREADY_LOGGED);
        } else {
            updateGameServerInfo(gsi);
        }
    }

    private GameServerInfo processNewGameServer(GameServerManager gameServerManager) {
        if (acceptNewGameServerEnabled() && acceptAlternativeId) {
            GameServerInfo gsi = new GameServerInfo(desiredId, client);
            if (gameServerManager.registerWithFirstAvaliableId(gsi)) {
               updateGameServerInfo(gsi);
                gameServerManager.registerServerOnDB(gsi);
                return  gsi;
            } else {
                client.close(FailReason.REASON_NO_FREE_ID);
            }
        } else {
            client.close(FailReason.NOT_AUTHED);
        }
        return null;
    }

    private void updateGameServerInfo(GameServerInfo gsi) {
        client.setGameServerInfo(gsi);
        client.setState(ServerClientState.AUTHED);
        gsi.setClient(client);
        gsi.setPorts(ports);
        gsi.setHosts(hosts);
        gsi.setMaxPlayers(maxPlayers);
        gsi.setAuthed(true);
        gsi.setServerType(serverType);
        getDAO(GameserverDAO.class).updateServerType(gsi.getId(), serverType);
        gsi.setAgeLimit(ageLimit);
        gsi.setShowingBrackets(showBrackets);
        gsi.setIsPvp(isPvp);
        gsi.setStatus(ServerStatus.STATUS_AUTO);
    }
}
