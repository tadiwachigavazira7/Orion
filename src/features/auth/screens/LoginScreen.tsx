import { useLocalSearchParams, useRouter } from 'expo-router';
import { useEffect, useRef, useState } from 'react';
import { Animated, Image, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
// 1. Import your shared styles
import { authStyles as styles } from '../authStyles'; // Adjust path if needed to find your authStyles file

type AuthType = 'login' | 'signup' | null;

export function LoginScreen() {
  const { type } = useLocalSearchParams();
  const titleOpacity = useRef(new Animated.Value(0)).current;
  const buttonOpacity = useRef(new Animated.Value(0)).current;
  
  const logo = require('../../../../assets/images/OrionLogo.png');
  
  const [selected, setSelected] = useState<AuthType>(null);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  
  const router = useRouter();

  useEffect(() => {
    if (type === 'login' || type === 'signup') {
      setSelected(type);
    }
  }, [type]);

  useEffect(() => {
    if (!selected) return;

    if (selected === 'login') {
      router.replace('/consumer-home-screen');
    } else if (selected === 'signup') {
      router.push('/sign-up');
    }
  }, [selected]);

  return (
    <View style={styles.container}>
      {/* 2. Apply the identical fixed position and custom top override matching your other pages */}
      <Image 
        source={logo} 
        style={[styles.fixedLogo, { top: 40 }]} 
        resizeMode="contain"
      />

      {/* 3. Add a wrapper margin on your first element so it drops below the absolute logo */}
      <View style={[styles.inputContainer, { marginTop: 100 }]}>
        <Text style={styles.label}>Email</Text>
        <TextInput
          style={styles.input}
          placeholder="Enter your email"
          placeholderTextColor="#A3A3A3"
          value={email}
          onChangeText={setEmail}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>

      {/* Password Section */}
      <View style={styles.inputContainer}>
        <Text style={styles.label}>Password</Text>
        <TextInput
          style={styles.input}
          placeholder="Enter your password"
          placeholderTextColor="#A3A3A3"
          value={password}
          onChangeText={setPassword}
          secureTextEntry={true}
          autoCapitalize="none"
          autoCorrect={false}
        />
      </View>

      {/* Primary Login Button */}
      <Pressable
        onPress={() => setSelected('login')}
        style={({ pressed }) => [
          styles.button,
          selected === 'login' && styles.buttonSelected,
          {
            opacity: pressed ? 0.8 : 1,
            transform: [{ scale: pressed ? 0.97 : 1 }],
          },
        ]}
      >
        <Text style={[styles.buttonText, selected === 'login' && styles.buttonTextSelected]}> 
          Login
        </Text>
      </Pressable>

      {/* Signup Section Container */}
      <View style={localStyles.signupContainer}>
        <Text style={localStyles.signupText}>First-time user?</Text>
        <Pressable
          onPress={() => setSelected('signup')}
          style={({ pressed }) => [
            localStyles.signupButton,
            {
              opacity: pressed ? 0.7 : 1,
            },
          ]}
        >
          <Text style={localStyles.signupButtonText}>Sign Up</Text>
        </Pressable>
      </View>
    </View>
  );
}

// 4. Retain only your unique login screen text styles down here
const localStyles = StyleSheet.create({
  signupContainer: {
    marginTop: 40,
    alignItems: 'center',
    justifyContent: 'center',
  },
  signupText: {
    fontSize: 14,
    color: '#666666',
    marginBottom: 6,
  },
  signupButton: {
    paddingVertical: 8,
    paddingHorizontal: 16,
  },
  signupButtonText: {
    fontSize: 15,
    fontWeight: '600',
    color: 'black',
    textDecorationLine: 'underline',
  },
});