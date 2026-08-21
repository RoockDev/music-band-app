package com.banda.sheetmusic;

import com.banda.users.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SheetMusicianAccessRepository extends JpaRepository<SheetMusicianAccess, Long> {

    /** {@link SheetMusicAccessService#canAccess} uses this for the "individually scoped in
     * {@code sheet_musician_access}" arm of the union check. */
    boolean existsBySheetMusicAndMusician(SheetMusic sheetMusic, UserAccount musician);

    long countByMusician(UserAccount musician);
}
