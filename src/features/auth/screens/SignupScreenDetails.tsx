// src/features/auth/screens/SignupDetailsScreen.tsx
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useState } from 'react';
import { Image, Pressable, Text, TextInput, View } from 'react-native';
import { authStyles as styles } from '../authStyles';


export function SignupDetailsScreen() {
  // 1. Pull the credentials passed from the previous screen via Expo Router
  const { email, password } = useLocalSearchParams();

  const [firstName, setFirstName] = useState('');
  const [lastName, setLastName] = useState('');
  const router = useRouter();

  const logo = require('../../../../assets/images/OrionLogo.png');

  const handleFinishSignUp = () => {
    if (!firstName || !lastName) {
      alert('Please fill out all fields');
      return;
    }

    // Future Auth Team: Your full registration payload is ready here
    console.log('Final Registration Payload:', {
      email,
      password,
      firstName,
      lastName,
    });

    // Complete registration and redirect to the application home dashboard
    router.replace('/consumer-profile');
  };

  return (
    <View style={styles.container}>
      <Image source={logo} style={styles.fixedLogo} resizeMode="contain" />

      <Text style={styles.headerTitle}>Tell us about yourself</Text>

      {/* First Name Input */}
      <View style={styles.inputContainer}>
        <Text style={styles.label}>First Name</Text>
        <TextInput
          style={styles.input}
          placeholder="Enter your first name"
          placeholderTextColor="#A3A3A3"
          value={firstName}
          onChangeText={setFirstName}
          autoCapitalize="words"
          autoCorrect={false}
        />
      </View>

      {/* Last Name Input */}
      <View style={styles.inputContainer}>
        <Text style={styles.label}>Last Name</Text>
        <TextInput
          style={styles.input}
          placeholder="Enter your last name"
          placeholderTextColor="#A3A3A3"
          value={lastName}
          onChangeText={setLastName}
          autoCapitalize="words"
          autoCorrect={false}
        />
      </View>

      {/* Complete Button */}
      <Pressable onPress={handleFinishSignUp} style={styles.button}>
        <Text style={styles.buttonText}>Complete Sign Up</Text>
      </Pressable>

      {/* Optional Back Button to step 1 */}
      <Pressable onPress={() => router.back()} style={styles.backButton}>
        <Text style={styles.backButtonText}>Back</Text>
      </Pressable>
    </View>
  );
}

