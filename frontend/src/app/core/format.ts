import { Pipe, PipeTransform } from '@angular/core';

const SA_TZ = 'Africa/Johannesburg';

/** Turns an enum constant such as CONSULTING_SERVICES into "Consulting services". */
@Pipe({ name: 'label' })
export class LabelPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return humanise(value);
  }
}

export function humanise(value: string | null | undefined): string {
  if (!value) return '—';
  const special: Record<string, string> = {
    RFQ: 'RFQ', RFP: 'RFP', RFI: 'RFI', EOI: 'EOI', ESD_PROGRAMME: 'ESD programme', LAND_LEASE: 'State land lease', KWAZULU_NATAL: 'KwaZulu-Natal',
    ACTIVE_API: 'Active (API)', ACTIVE_PUBLIC: 'Active (public)', CSD: 'CSD', CIDB: 'CIDB',
    EASTERN_CAPE: 'Eastern Cape', FREE_STATE: 'Free State', NORTH_WEST: 'North West', NORTHERN_CAPE: 'Northern Cape',
    WESTERN_CAPE: 'Western Cape', SUPPLIER_INVITATION: 'Supplier invitation', ELECTRONIC_OR_PHYSICAL: 'Electronic or physical',
  };
  if (special[value]) return special[value];
  const text = value.toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * Formats a closing date/time in South African time. When the source gives only a date, only the date is
 * shown, so the platform never displays a time the source did not publish.
 */
export function formatClosing(closingDate: string | null, closingAt: string | null, timeSpecified: boolean): string {
  if (closingAt && timeSpecified) {
    return new Intl.DateTimeFormat('en-ZA', { timeZone: SA_TZ, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(closingAt)) + ' SAST';
  }
  if (closingDate) {
    const [y, m, d] = closingDate.split('-').map(Number);
    return new Intl.DateTimeFormat('en-ZA', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, d)));
  }
  return 'Not specified';
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—';
  return new Intl.DateTimeFormat('en-ZA', { timeZone: SA_TZ, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
}

@Pipe({ name: 'dateTime' })
export class DateTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return formatDateTime(value);
  }
}

export function relativeDays(days: number | null): string {
  if (days === null) return 'No closing date';
  if (days < 0) return 'Closed';
  if (days === 0) return 'Closes today';
  if (days === 1) return 'Closes tomorrow';
  return `Closes in ${days} days`;
}

/** Closing date and time as separate table-cell parts (South African time). Time is null when the source gave none. */
export function closingParts(closingDate: string | null, closingAt: string | null, timeSpecified: boolean): { date: string; time: string | null } {
  if (closingAt && timeSpecified) {
    const d = new Date(closingAt);
    return {
      date: new Intl.DateTimeFormat('en-ZA', { timeZone: SA_TZ, day: 'numeric', month: 'short', year: 'numeric' }).format(d).replace(/^0/, ''),
      time: new Intl.DateTimeFormat('en-ZA', { timeZone: SA_TZ, hour: '2-digit', minute: '2-digit', hour12: false }).format(d),
    };
  }
  if (closingDate) {
    const [y, m, day] = closingDate.split('-').map(Number);
    return { date: new Intl.DateTimeFormat('en-ZA', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(Date.UTC(y, m - 1, day))).replace(/^0/, ''), time: null };
  }
  return { date: 'Not specified', time: null };
}

const SMALL_WORDS = new Set(['a', 'an', 'and', 'as', 'at', 'by', 'for', 'from', 'in', 'into', 'of', 'on', 'or', 'per', 'the', 'to', 'via', 'with', 'within']);
const ACRONYMS = new Set(['ICT', 'IT', 'CPD', 'ECSA', 'SAPS', 'HVAC', 'CCTV', 'PPE', 'SANS', 'CIDB', 'BBBEE', 'B-BBEE', 'SMME', 'SMMES', 'RFQ',
  'RFP', 'RFI', 'EOI', 'SLA', 'ERP', 'SAP', 'LAN', 'WAN', 'UPS', 'PV', 'KZN', 'DBSA', 'SANRAL', 'PRASA', 'SITA', 'SARS', 'NHLS', 'CSIR',
  'SABC', 'TNPA', 'ACSA', 'SAA', 'IDT', 'DPWI', 'OHS', 'SHEQ', 'ISO', 'GIS', 'HR', 'PABX', 'VOIP', 'WIFI', 'ATM', 'CBD', 'SA', 'RSA',
  'LED', 'IP', 'AV', 'DC', 'AC', 'HV', 'MV', 'LV', 'UPS', 'CSD', 'SCM', 'PFMA', 'MFMA', 'PPPFA', 'NEC', 'GCC', 'JBCC', 'FIDIC', 'ID',
  'II', 'III', 'IV', 'VI', 'VII', 'VIII', 'IX', 'XI', 'XII', 'PTY', 'LTD', 'NPC', 'SOC', 'TVET', 'NSFAS', 'SETA', 'PLAS', 'GPS', 'IOT']);

/**
 * Display-only: many sources publish titles in capitals, which is hard to read in a list. Titles that are almost
 * entirely upper case are shown in title case (known acronyms, codes with digits and short bracketed acronyms are
 * kept). The text as published is always available (tooltip and detail page).
 */
export function readableTitle(title: string | null | undefined): string {
  if (!title) return '';
  const letters = title.replace(/[^A-Za-z]/g, '');
  if (letters.length < 12) return title;
  const upper = letters.replace(/[^A-Z]/g, '').length;
  if (upper / letters.length < 0.8) return title;
  let first = true;
  return title.replace(/[A-Za-z][A-Za-z'’\-]*/g, (word, offset: number) => {
    const bare = word.toUpperCase();
    const before = title.slice(Math.max(0, offset - 1), offset);
    const after = title.charAt(offset + word.length);
    const isFirst = first;
    first = false;
    if (ACRONYMS.has(bare) || /\d/.test(title.slice(Math.max(0, offset - 1), offset + word.length + 1)) && word.length <= 4) return bare;
    if (before === '(' && after === ')' && word.length <= 6) return bare;
    const lower = word.toLowerCase();
    if (!isFirst && SMALL_WORDS.has(lower) && !/[:.(\-–]\s*$/.test(title.slice(0, offset))) return lower;
    return lower.replace(/(^|[-'’])([a-z])/g, (_m, p: string, c: string) => p + c.toUpperCase());
  });
}
