package com.banda.events;

import com.banda.groups.Group;
import com.banda.users.UserAccount;

import java.util.List;

/** Valid group and individual targets available to an event manager. */
public record EventTargetCatalog(List<Group> groups, List<UserAccount> musicians) {
}
