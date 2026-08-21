import { FormControl, FormGroup } from '@angular/forms';
import {
  deduplicateIds,
  describeScopeImpact,
  eventScopeValidator,
  scopeRemovesRecipients,
} from './event-scope.utils';

describe('event scope logic', () => {
  it('deduplicates target IDs and rejects global scope combined with explicit targets', () => {
    expect(deduplicateIds([4, 4, 7])).toEqual([4, 7]);
    const form = new FormGroup(
      {
        allScope: new FormControl(true),
        groupIds: new FormControl([4]),
        musicianIds: new FormControl<number[]>([]),
      },
      eventScopeValidator,
    );

    expect(form.hasError('ambiguousScope')).toBe(true);
  });

  it('detects recipient removal for all-to-limited and explicit target removal', () => {
    expect(
      scopeRemovesRecipients(
        { allScope: true, groupIds: [], musicianIds: [] },
        { allScope: false, groupIds: [2], musicianIds: [] },
      ),
    ).toBe(true);
    expect(
      scopeRemovesRecipients(
        { allScope: false, groupIds: [2, 3], musicianIds: [8] },
        { allScope: false, groupIds: [3], musicianIds: [8, 9] },
      ),
    ).toBe(true);
    expect(
      scopeRemovesRecipients(
        { allScope: false, groupIds: [2], musicianIds: [] },
        { allScope: true, groupIds: [], musicianIds: [] },
      ),
    ).toBe(false);
  });

  it('summarizes unchanged and changed scope before saving', () => {
    const scope = { allScope: false, groupIds: [2], musicianIds: [8] };
    expect(describeScopeImpact(scope, scope)).toContain('Sin cambios de alcance');
    expect(
      describeScopeImpact(scope, { allScope: false, groupIds: [], musicianIds: [8] }),
    ).toContain('El alcance cambiará');
  });
});
