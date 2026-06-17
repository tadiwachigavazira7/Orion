// src/features/auth/screens/SignupScreen.tsx
import { useRouter } from 'expo-router';
import { useState } from 'react';
import { Image, Pressable, Text, TextInput, View } from 'react-native';
import { authStyles as styles } from '../authStyles';


export function SignupScreen() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const router = useRouter();

  const logo = require('../../../../assets/images/OrionLogo.png');

  return (
    <View style={styles.container}>
      <Image source={logo} style={styles.fixedLogo} resizeMode="contain" />

      <Text style={styles.headerTitle}>Create Account</Text>

      {/* Email Input */}
      <View style={styles.inputContainer}>
        <Text style={styles.label}>Email</Text>
        <TextInput
          style={styles.input}
          placeholder="Enter your email"
          placeholderTextColor="#A3A3A3"
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
        />
      </View>

      {/* Password Input */}
      <View style={styles.inputContainer}>
        <Text style={styles.label}>Password</Text>
        <TextInput
          style={styles.input}
          placeholder="Create a password"
          placeholderTextColor="#A3A3A3"
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoCapitalize="none"
        />
      </View>

      {/* Register Button */}
        <Pressable 
            onPress={() => {
                if (!email || !password) return alert('Please fill out all fields');
                
                router.push({
                pathname: '/sign-up-screen-details',
                params: { email, password } // Safely pass down step 1's values
                });
            }}
            style={styles.button}
            >
            <Text style={styles.buttonText}>Next</Text>
        </Pressable>
    </View>
  );
}

