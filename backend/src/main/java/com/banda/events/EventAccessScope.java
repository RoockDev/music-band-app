package com.banda.events;

import java.util.List;

/** Complete internal-calendar scope, exposed only through event-management use cases. */
public record EventAccessScope(List<Long> groupIds, List<Long> musicianIds) {
}
