/*
 * Copyright 2014 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.plugin.powertools;

import java.util.ArrayList;
import java.util.List;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import com.gitblit.Constants.AccessPermission;
import com.gitblit.GitBlitException;
import com.gitblit.manager.IGitblit;
import com.gitblit.models.RegistrantAccessPermission;
import com.gitblit.models.RepositoryModel;
import com.gitblit.models.TeamModel;
import com.gitblit.models.UserModel;
import com.gitblit.transport.ssh.commands.CommandMetaData;
import com.gitblit.transport.ssh.commands.DispatchCommand;
import com.gitblit.transport.ssh.commands.ListFilterCommand;
import com.gitblit.transport.ssh.commands.SshCommand;
import com.gitblit.transport.ssh.commands.UsageExample;
import com.gitblit.transport.ssh.commands.UsageExamples;
import com.gitblit.utils.ArrayUtils;
import com.gitblit.utils.FlipTable;
import com.gitblit.utils.FlipTable.Borders;
import com.gitblit.utils.StringUtils;
import com.google.common.base.Joiner;

@CommandMetaData(name = "teams", description = "Team management commands", admin = true)
public class TeamsDispatcher extends DispatchCommand {

	@Override
	protected void setup() {
		// primary team commands
		register(NewTeam.class);
		register(RenameTeam.class);
		register(RemoveTeam.class);
		register(ShowTeam.class);
		register(ListTeams.class);

		// team-specific commands
		register(SetField.class);
		register(Permissions.class);
		register(Members.class);
	}

	public static abstract class TeamCommand extends SshCommand {
		@Argument(index = 0, required = true, metaVar = "TEAM", usage = "team name")
		protected String teamname;

		protected TeamModel getTeam(boolean requireTeam) throws UnloggedFailure {
			IGitblit gitblit = getContext().getGitblit();
			TeamModel team = gitblit.getTeamModel(teamname);
			if (requireTeam && team == null) {
				throw new UnloggedFailure(1, String.format("Team %s does not exist!", teamname));
			}
			return team;
		}
	}

	@CommandMetaData(name = "new", aliases = { "add" }, description = "Create a new team")
	@UsageExample(syntax = "${cmd} contributors --canFork --canCreate")
	public static class NewTeam extends TeamCommand {

		@Option(name = "--canAdmin", usage = "can administer the server")
		protected boolean canAdmin;

		@Option(name = "--canFork", usage = "can fork repositories")
		protected boolean canFork;

		@Option(name = "--canCreate", usage = "can create personal repositories")
		protected boolean canCreate;

		@Override
		public void run() throws UnloggedFailure {

			if (getTeam(false) != null) {
				throw new UnloggedFailure(1, String.format("Team %s already exists!", teamname));
			}

			TeamModel team = new TeamModel(teamname);
			team.canAdmin = canAdmin;
			team.canFork = canFork;
			team.canCreate = canCreate;

			IGitblit gitblit = getContext().getGitblit();
			try {
				gitblit.addTeam(team);
				stdout.println(String.format("%s created.", teamname));
			} catch (GitBlitException e) {
				String msg = String.format("Failed to create %s!", teamname);
				log.error(msg, e);
				throw new UnloggedFailure(1, msg);
			}
		}
	}

	@CommandMetaData(name = "rename", aliases = { "mv" }, description = "Rename a team")
	@UsageExample(syntax = "${cmd} contributors friends", description = "Rename the contributors team to the friends team")
	public static class RenameTeam extends TeamCommand {
		@Argument(index = 1, required = true, metaVar = "NEWNAME", usage = "the new team name")
		protected String newTeamName;

				@Override
		public void run() throws UnloggedFailure {
			TeamModel team = getTeam(true);
			IGitblit gitblit = getContext().getGitblit();
			if (null != gitblit.getTeamModel(newTeamName)) {
				throw new UnloggedFailure(1, String.format("Team %s already exists!", newTeamName));
			}

			// set the new team name
			team.name = newTeamName;

			try {
				gitblit.reviseTeam(teamname, team);
				stdout.println(String.format("Renamed team %s to %s.", teamname, newTeamName));
			} catch (GitBlitException e) {
				String msg = String.format("Failed to rename team from %s to %s", teamname, newTeamName);
				log.error(msg, e);
				throw new UnloggedFailure(1, msg);
			}
		}
	}

	@CommandMetaData(name = "set", description = "Set the specified field of a team")
	@UsageExample(syntax = "${cmd} contributors canFork true", description = "Allow the contributors team to fork repositories")
	public static class SetField extends TeamCommand {

		@Argument(index = 1, required = true, metaVar = "FIELD", usage = "the field to update")
		protected String fieldName;

		@Argument(index = 2, required = true, metaVar = "VALUE", usage = "the new value")
		protected List<String> fieldValues = new ArrayList<String>();

		protected enum Field {
			mailingList, preReceive, postReceive, canAdmin, canFork, canCreate;

			static Field fromString(String name) {
				for (Field field : values()) {
					if (field.name().equalsIgnoreCase(name)) {
						return field;
					}
				}
				return null;
			}
		}

		@Override
		protected String getUsageText() {
			String fields = Joiner.on(", ").join(Field.values());
			StringBuilder sb = new StringBuilder();
			sb.append("Valid fields are:\n    ").append(fields);
			return sb.toString();
		}

		@Override
		public void run() throws UnloggedFailure {
			TeamModel team = getTeam(true);

			Field field = Field.fromString(fieldName);
			if (field == null) {
				throw new UnloggedFailure(1, String.format("Unknown field %s", fieldName));
			}

			String value = Joiner.on(" ").join(fieldValues);
			IGitblit gitblit = getContext().getGitblit();

			switch(field) {
			case mailingList:
				team.mailingLists.clear();
				team.mailingLists.addAll(fieldValues);
				break;
			case preReceive:
				team.preReceiveScripts.clear();
				team.preReceiveScripts.addAll(fieldValues);
				break;
			case postReceive:
				team.postReceiveScripts.clear();
				team.postReceiveScripts.addAll(fieldValues);
				break;
			case canAdmin:
				team.canAdmin = toBool(value);
				break;
			case canFork:
				team.canFork = toBool(value);
				break;
			case canCreate:
				team.canCreate = toBool(value);
				break;
			default:
				throw new UnloggedFailure(1,  String.format("Field %s was not properly handled by the set command.", fieldName));
			}

			try {
				gitblit.reviseTeam(teamname, team);
				stdout.println(String.format("Set %s.%s = %s", teamname, fieldName, value));
			} catch (GitBlitException e) {
				String msg = String.format("Failed to set %s.%s = %s", teamname, fieldName, value);
				log.error(msg, e);
				throw new UnloggedFailure(1, msg);
			}
		}

		protected boolean toBool(String value) throws UnloggedFailure {
			String v = value.toLowerCase();
			if (v.equals("t")
					|| v.equals("true")
					|| v.equals("yes")
					|| v.equals("on")
					|| v.equals("y")
					|| v.equals("1")) {
				return true;
			} else if (v.equals("f")
					|| v.equals("false")
					|| v.equals("no")
					|| v.equals("off")
					|| v.equals("n")
					|| v.equals("0")) {
				return false;
			}
			throw new UnloggedFailure(1,  String.format("Invalid boolean value %s", value));
		}
	}

	@CommandMetaData(name = "permissions", aliases = { "perms" }, description = "Add or remove permissions from a team")
	@UsageExample(syntax = "${cmd} contributors RW:alpha/repo.git RWC:alpha/repo2.git", description = "Add or set permissions for contributors")
	public static class Permissions extends TeamCommand {

		@Argument(index = 1, multiValued = true, metaVar = "[PERMISSION:]REPOSITORY", usage = "a repository expression")
		protected List<String> permissions;

		@Option(name = "--remove", aliases = { "-r" }, metaVar = "REPOSITORY|ALL", usage = "remove a repository permission")
		protected List<String> removals;

		@Override
		public void run() throws UnloggedFailure {
			IGitblit gitblit = getContext().getGitblit();
			TeamModel team = getTeam(true);

			boolean modified = false;
			if (!ArrayUtils.isEmpty(removals)) {
				if (removals.contains("ALL")) {
					team.permissions.clear();
				} else {
					for (String repo : removals) {
						team.removeRepositoryPermission(repo);
						log.info(String.format("Removing permission for %s from %s", repo, teamname));
					}
				}
				modified = true;
			}

			if (!ArrayUtils.isEmpty(permissions)) {
				for (String perm : permissions) {
					String repo = AccessPermission.repositoryFromRole(perm);
					if (StringUtils.findInvalidCharacter(repo) == null) {
						// explicit permision, confirm repository
						RepositoryModel r = gitblit.getRepositoryModel(repo);
						if (r == null) {
							throw new UnloggedFailure(1, String.format("Repository %s does not exist!", repo));
						}
					}
					AccessPermission ap = AccessPermission.permissionFromRole(perm);
					team.setRepositoryPermission(repo, ap);
					log.info(String.format("Setting %s:%s for %s", ap.name(), repo, teamname));
				}
				modified = true;
			}

			if (modified && gitblit.updateTeamModel(teamname, team)) {
				// reload & display new permissions
				team = gitblit.getTeamModel(teamname);
			}

			showPermissions(team);
		}

		protected void showPermissions(TeamModel team) {
			List<RegistrantAccessPermission> perms = team.getRepositoryPermissions();
			String[] pheaders = { "Repository", "Permission", "Type" };
			Object [][] pdata = new Object[perms.size()][];
			for (int i = 0; i < perms.size(); i++) {
				RegistrantAccessPermission ap = perms.get(i);
				pdata[i] = new Object[] { ap.registrant, ap.permission, ap.permissionType };
			}
			stdout.println(FlipTable.of(pheaders, pdata, Borders.BODY_HCOLS));
		}
	}

	@CommandMetaData(name = "members", aliases = { "users" }, description = "Add or remove team members")
	@UsageExample(syntax = "${cmd} contributors RW:alpha/repo.git RWC:alpha/repo2.git", description = "Add or set permissions for contributors")
	public static class Members extends TeamCommand {

		@Argument(index = 1, multiValued = true, metaVar = "USERNAME", usage = "a username")
		protected List<String> members;

		@Option(name = "--remove", aliases = { "-r" }, metaVar = "USERNAME|ALL", usage = "remove a team member")
		protected List<String> removals;

		@Override
		public void run() throws UnloggedFailure {
			IGitblit gitblit = getContext().getGitblit();
			TeamModel team = getTeam(true);

			boolean canEditMemberships = gitblit.supportsTeamMembershipChanges(team);
			if (!canEditMemberships) {
				String msg = String.format("Team %s (%s) does not permit membership changes!", team.name, team.accountType);
				throw new UnloggedFailure(1, msg);
			}

			boolean modified = false;
			if (!ArrayUtils.isEmpty(removals)) {
				if (removals.contains("ALL")) {
					team.users.clear();
				} else {
					for (String member : removals) {
						team.removeUser(member);
						log.info(String.format("Removing member %s from %s", member, teamname));
					}
				}
				modified = true;
			}

			if (!ArrayUtils.isEmpty(members)) {
				for (String username : members) {
					UserModel u = gitblit.getUserModel(username);
					if (u == null) {
						throw new UnloggedFailure(1,  String.format("Unknown user %s", username));
					}
					boolean canEditTeams = gitblit.supportsTeamMembershipChanges(u);
					if (!canEditTeams) {
						String msg = String.format("User %s (%s) does not allow team membership changes ", u.username, u.accountType);
						throw new UnloggedFailure(1, msg);
					}
					team.addUser(username);
				}
				modified = true;
			}

			if (modified && gitblit.updateTeamModel(teamname, team)) {
				// reload & display new permissions
				team = gitblit.getTeamModel(teamname);
			}

			String[] headers = { "Username", "Display Name" };
			Object [][] data = new Object[team.users.size()][];
			int i = 0;
			for (String username : team.users) {
				UserModel u = gitblit.getUserModel(username);
				data[i] = new Object[] { username, u.displayName };
				i++;
			}
			stdout.println(FlipTable.of(headers, data, Borders.BODY_HCOLS));
		}
	}

	@CommandMetaData(name = "remove", aliases = { "rm" }, description = "Remove a team")
	@UsageExample(syntax = "${cmd} contributors", description = "Delete the contributors team")
	public static class RemoveTeam extends TeamCommand {

		@Override
		public void run() throws UnloggedFailure {

			TeamModel team = getTeam(true);
			IGitblit gitblit = getContext().getGitblit();
			if (gitblit.deleteTeamModel(team)) {
				stdout.println(String.format("%s has been deleted.", teamname));
			} else {
				throw new UnloggedFailure(1, String.format("Failed to delete %s!", teamname));
			}
		}
	}

	@CommandMetaData(name = "show", description = "Show the details of a team")
	@UsageExample(syntax = "${cmd} contributors", description = "Display the 'contributors' team")
	public static class ShowTeam extends TeamCommand {

		@Override
		public void run() throws UnloggedFailure {

			TeamModel t = getTeam(true);

			// fields
			StringBuilder fb = new StringBuilder();
			fb.append("Mailing Lists : ").append(Joiner.on(", ").join(t.mailingLists)).append('\n');
			fb.append("Type          : ").append(t.accountType).append('\n');
			fb.append("Can Admin     : ").append(t.canAdmin ? "Y":"").append('\n');
			fb.append("Can Fork      : ").append(t.canFork ? "Y":"").append('\n');
			fb.append("Can Create    : ").append(t.canCreate ? "Y":"").append('\n');
			fb.append("Pre-Receive   : ").append(Joiner.on(", ").join(t.preReceiveScripts)).append('\n');
			fb.append("Post-Receive  : ").append(Joiner.on(", ").join(t.postReceiveScripts)).append('\n');
			String fields = fb.toString();

			// members
			String members;
			if (t.users.size() == 0) {
				members = FlipTable.EMPTY;
			} else {
				IGitblit gitblit = getContext().getGitblit();
				String[] headers = { "Username", "Display Name" };
				Object [][] data = new Object[t.users.size()][];
				int i = 0;
				for (String username : t.users) {
					UserModel u = gitblit.getUserModel(username);
					data[i] = new Object[] { username,  u == null ? null : u.displayName };
					i++;
				}
				members = FlipTable.of(headers, data, Borders.COLS);
			}

			// permissions
			List<RegistrantAccessPermission> perms = t.getRepositoryPermissions();
			String permissions;
			if (perms.isEmpty()) {
				permissions = FlipTable.EMPTY;
			} else {
				String[] pheaders = { "Repository", "Permission", "Type" };
				Object [][] pdata = new Object[perms.size()][];
				for (int i = 0; i < perms.size(); i++) {
					RegistrantAccessPermission ap = perms.get(i);
					pdata[i] = new Object[] { ap.registrant, ap.permission, ap.permissionType };
				}
				permissions = FlipTable.of(pheaders, pdata, Borders.COLS);
			}

			// assemble team table
			String [] headers = new String[] { t.name };
			String[][] data = new String[6][];
			data[0] = new String [] { "FIELDS" };
			data[1] = new String [] { fields };
			data[2] = new String [] { "MEMBERS" };
			data[3] = new String [] { members };
			data[4] = new String [] { "PERMISSIONS" };
			data[5] = new String [] { permissions };
			stdout.println(FlipTable.of(headers, data));
		}
	}

	@CommandMetaData(name = "list", aliases= { "ls" }, description = "List teams")
	@UsageExamples(examples = {
		@UsageExample(syntax = "${cmd}", description = "List teams as a table"),
		@UsageExample(syntax = "${cmd} j.*", description = "List all teams that start with 'j'"),
	})
	public static class ListTeams extends ListFilterCommand<TeamModel> {

		@Override
		protected List<TeamModel> getItems() {
			IGitblit gitblit = getContext().getGitblit();
			List<TeamModel> teams = gitblit.getAllTeams();
			return teams;
		}

		@Override
		protected boolean matches(String filter, TeamModel t) {
			return t.name.matches(filter);
		}

		@Override
		protected void asTable(List<TeamModel> list) {
			String[] headers = { "Name", "Members", "Type", "Create?", "Fork?"};
			Object[][] data = new Object[list.size()][];
			for (int i = 0; i < list.size(); i++) {
				TeamModel t = list.get(i);
				data[i] = new Object[] {
						(t.canAdmin ? "*" : " ") + t.name,
						t.users.isEmpty() ? "" : t.users.size(),
						t.accountType + (t.canAdmin ? ",admin":""),
						(t.canAdmin || t.canCreate) ? "Y":"",
						(t.canAdmin || t.canFork) ? "Y" : ""};
			}
			stdout.println(FlipTable.of(headers, data, Borders.BODY_HCOLS));
		}

		@Override
		protected void asTabbed(List<TeamModel> teams) {
			if (verbose) {
				for (TeamModel t : teams) {
					outTabbed(
							t.name,
							t.users.isEmpty() ? "" : t.users.size(),
							t.accountType + (t.canAdmin ? ",admin":""),
							(t.canAdmin || t.canCreate) ? "Y":"",
							(t.canAdmin || t.canFork) ? "Y" : "");
				}
			} else {
				for (TeamModel u : teams) {
					outTabbed((u.canAdmin ? "*" : " ") + u.name);
				}
			}
		}
	}
}