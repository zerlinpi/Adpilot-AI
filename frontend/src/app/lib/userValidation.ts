// User input validation mirroring the backend UserValidation helper (Req 5.1, 5.3).
// Keeping the rules in one place lets the add-user form surface field-level
// messages before a request is ever sent to the backend.

export const NAME_MIN_LENGTH = 1;
export const NAME_MAX_LENGTH = 255;
export const EMAIL_MAX_LENGTH = 320;

/** A name is valid when its length is within [1, 255] (Req 5.1, 5.3). */
export function isValidName(name: string): boolean {
  if (name == null) return false;
  const len = name.length;
  return len >= NAME_MIN_LENGTH && len <= NAME_MAX_LENGTH;
}

/**
 * An email is valid when it is non-empty, at most 320 characters, contains exactly
 * one "@", and has a non-empty local part and a non-empty domain part (Req 5.1, 5.3).
 */
export function isValidEmail(email: string): boolean {
  if (email == null) return false;
  if (email.length === 0 || email.length > EMAIL_MAX_LENGTH) return false;
  const firstAt = email.indexOf('@');
  const lastAt = email.lastIndexOf('@');
  if (firstAt < 0 || firstAt !== lastAt) return false;
  const local = email.slice(0, firstAt);
  const domain = email.slice(firstAt + 1);
  return local.length > 0 && domain.length > 0;
}

export interface UserFieldErrors {
  name?: string;
  email?: string;
}

/**
 * Validate the add-user form fields, returning a per-field error map. An empty map
 * means the form is valid and may be submitted (Req 5.3).
 */
export function validateUserForm(form: { name: string; email: string }): UserFieldErrors {
  const errors: UserFieldErrors = {};
  if (!form.name || form.name.trim().length === 0) {
    errors.name = '请输入姓名';
  } else if (!isValidName(form.name)) {
    errors.name = `姓名长度需在 ${NAME_MIN_LENGTH}-${NAME_MAX_LENGTH} 个字符之间`;
  }

  if (!form.email || form.email.length === 0) {
    errors.email = '请输入邮箱';
  } else if (!isValidEmail(form.email)) {
    errors.email = '请输入有效的邮箱地址（需包含单个 "@"，且本地名与域名非空，长度不超过 320）';
  }
  return errors;
}
