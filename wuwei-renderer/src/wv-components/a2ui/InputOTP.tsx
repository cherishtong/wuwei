import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { OTPInput } from 'input-otp';

const InputOTPApi = {
  name: 'InputOTP',
  schema: z.object({
    length: z.number().default(6).describe('Number of OTP slots.'),
    value: z.union([z.string(), z.object({ path: z.string() })]).optional().describe('Current OTP value or DataModel path binding.'),
    mask: z.boolean().default(true).describe('Mask input characters with dots.'),
  }).strict(),
};

function InputOTPComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const length = (props.length as number) || 6;
  const mask = props.mask !== false;
  const resolvedValue = typeof props.value === 'string' ? props.value as string : '';
  const [value, setValue] = useState(resolvedValue);

  useEffect(() => { setValue(typeof props.value === 'string' ? props.value as string : ''); }, [props.value]);

  const handleChange = (val: string) => {
    setValue(val);
    props.setValue?.(val);
  };

  return (
    <OTPInput
      maxLength={length}
      value={value}
      onChange={handleChange}
      containerClassName="flex items-center gap-2 has-[:disabled]:opacity-50"
      render={({ slots }) => (
        <div className="flex items-center">
          {slots.map((slot, idx) => (
            <div
              key={idx}
              className={`relative flex h-10 w-10 items-center justify-center border-y border-r border-input text-sm transition-all first:rounded-l-md first:border-l last:rounded-r-md ${slot.isActive ? 'z-10 ring-2 ring-ring ring-offset-background' : ''}`}
            >
              {slot.char !== null ? (mask ? '●' : slot.char) : null}
              {slot.hasFakeCaret && (
                <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
                  <div className="h-4 w-px animate-caret-blink bg-foreground duration-1000" />
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    />
  );
}

export const WvA2uiInputOTP = createComponentImplementation(InputOTPApi as never, InputOTPComponent as never);
