package com.banda.sheetmusic;

import com.banda.groups.Group;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SheetGroupAccessRepository extends JpaRepository<SheetGroupAccess, Long> {

    /** {@link SheetMusicAccessService#canAccess} uses this for the "any group in
     * {@code sheet_group_access}" arm of the union check. */
    boolean existsBySheetMusicAndGroupIn(SheetMusic sheetMusic, List<Group> groups);

    List<SheetGroupAccess> findBySheetMusic(SheetMusic sheetMusic);

    long countByGroup(Group group);

    long deleteBySheetMusic(SheetMusic sheetMusic);
}
