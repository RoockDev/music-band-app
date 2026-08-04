package com.banda.sheetmusic;

import com.banda.groups.Group;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One {@link Group}'s scoped access to one {@link SheetMusic} piece — the
 * {@code sheet_group_access} explicit join table (Section 5/6, design doc's "Access joins
 * (explicit, NOT polymorphic)"). Every musician in this group can access the piece, per the
 * union (OR) semantics {@link SheetMusicAccessService#canAccess} implements. Follows the
 * exact {@code MusicianGroup} shape this PR was explicitly named to copy.
 */
@Entity
@Table(name = "sheet_group_access",
        uniqueConstraints = @UniqueConstraint(name = "uk_sheet_group_access_sheet_group",
                columnNames = {"sheet_music_id", "group_id"}))
public class SheetGroupAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sheet_music_id", nullable = false)
    private SheetMusic sheetMusic;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    protected SheetGroupAccess() {
        // JPA
    }

    public SheetGroupAccess(SheetMusic sheetMusic, Group group) {
        this.sheetMusic = sheetMusic;
        this.group = group;
    }

    public Long getId() {
        return id;
    }

    public SheetMusic getSheetMusic() {
        return sheetMusic;
    }

    public Group getGroup() {
        return group;
    }
}
