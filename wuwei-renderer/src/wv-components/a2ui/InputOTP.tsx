import { useState, useEffect } from 'react';
import { z } from 'zod';
import { createComponentImplementation } from '@a2ui/react/v0_9';
import { InputOTP as ShadcnInputOTP, InputOTPGroup, InputOTPSlot } from '@/wv-components/ui/input-otp';

const InputOTPApi = {
  name: 'InputOTP',
  schema: z.object({
    length: z.number().default(6).describe('Number of OTP slots.'),
    value: z.string().optional().describe('The current OTP value.'),
  }).strict(),
};

function InputOTPComponent({ props }: { props: Record<string, unknown> & { setValue?: (val: string) => void }; buildChild: (id: string, basePath?: string) => React.ReactNode; context: unknown }) {
  const length = (props.length as number) || 6;
  const [value, setValue] = useState((props.value as string) || '');

  useEffect(() => { setValue((props.value as string) || ''); }, [props.value]);

  const handleChange = (val: string) => {
    setValue(val);
    props.setValue?.(val);
  };

  return (
    <ShadcnInputOTP maxLength={length} value={value} onChange={handleChange}>
      <InputOTPGroup>
        {Array.from({ length }, (_, i) => (
          <InputOTPSlot key={i} index={i} />
        ))}
      </InputOTPGroup>
    </ShadcnInputOTP>
  );
}

export const WvA2uiInputOTP = createComponentImplementation(InputOTPApi as never, InputOTPComponent as never);
