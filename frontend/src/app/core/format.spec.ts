import { closingParts, formatClosing, humanise, readableTitle, relativeDays } from './format';

describe('format', () => {
  it('humanises enum constants', () => {
    expect(humanise('CONSULTING_SERVICES')).toBe('Consulting services');
    expect(humanise('RFQ')).toBe('RFQ');
    expect(humanise('KWAZULU_NATAL')).toBe('KwaZulu-Natal');
    expect(humanise(null)).toBe('—');
  });

  it('shows a closing time only when the source published one, in SAST', () => {
    expect(formatClosing('2026-12-08', '2026-12-08T09:00:00Z', true)).toContain('11:00');
    expect(formatClosing('2026-12-08', '2026-12-08T09:00:00Z', true)).toContain('SAST');
    const dateOnly = formatClosing('2026-12-08', null, false);
    expect(dateOnly).not.toContain(':');
    expect(dateOnly).toContain('2026');
    expect(formatClosing(null, null, false)).toBe('Not specified');
  });

  it('describes days until closing', () => {
    expect(relativeDays(null)).toBe('No closing date');
    expect(relativeDays(-1)).toBe('Closed');
    expect(relativeDays(0)).toBe('Closes today');
    expect(relativeDays(1)).toBe('Closes tomorrow');
    expect(relativeDays(5)).toBe('Closes in 5 days');
  });

  it('tames all-capital titles for display, keeping acronyms and codes', () => {
    expect(readableTitle('ACCREDITATION OF ELECTRICAL ENGINEERING COURSES FOR CPD POINTS BY THE ENGINEERING COUNCIL OF SOUTH AFRICA (ECSA)'))
      .toBe('Accreditation of Electrical Engineering Courses for CPD Points by the Engineering Council of South Africa (ECSA)');
    expect(readableTitle('RE-ADVERTISEMENT: SUPPLY OF PPE FOR A PERIOD OF 36 MONTHS')).toBe('Re-Advertisement: Supply of PPE for a Period of 36 Months');
  });

  it('leaves normally cased and short titles exactly as published', () => {
    const t = 'Bidders List - Provision of specialised investigation services';
    expect(readableTitle(t)).toBe(t);
    expect(readableTitle('ICT TENDER')).toBe('ICT TENDER');
  });

  it('splits closing into date and time cells, with no time when none was published', () => {
    expect(closingParts('2026-12-08', '2026-12-08T09:00:00Z', true)).toEqual({ date: '8 Dec 2026', time: '11:00' });
    expect(closingParts('2026-12-08', null, false)).toEqual({ date: '8 Dec 2026', time: null });
    expect(humanise('WESTERN_CAPE')).toBe('Western Cape');
  });
});
